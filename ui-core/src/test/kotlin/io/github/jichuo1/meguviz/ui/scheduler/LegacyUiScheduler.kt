package io.github.jichuo1.meguviz.ui.scheduler

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Test-only oracle: a verbatim copy of `UiScheduler` at a6b8ba5 (before the discardInvalid
 * O(C^2) fix). [DiscardInvalidEquivalenceTest] drives it and the production scheduler with the
 * same reentrant programs and asserts identical observable behavior. Do not edit except to keep it
 * compiling.
 */
internal class LegacyUiScheduler(
    driver: UiFrameDriver,
    private val assertOwner: () -> Unit,
    val stateCapacity: Int = 256,
    val commandCapacity: Int = 256,
    val maxOperationsPerFrame: Int = 64,
    val maxFrameWorkNanos: Long = 2_000_000,
    private val nanoTime: () -> Long = System::nanoTime,
    private val dispatcher: OwnerThreadDispatcher? = null,
) : AutoCloseable {

    /**
     * The pre-1.2 seven-argument constructor, kept so existing Java callers stay binary compatible.
     *
     * It behaves exactly like the primary constructor with a null [dispatcher], i.e. owner-thread
     * only: the `submit*Async` entry points report [RejectionReason.CLOSED]. Source that used to
     * compile against the seven-argument form keeps compiling and keeps linking.
     */
    constructor(
        driver: UiFrameDriver,
        assertOwner: () -> Unit,
        stateCapacity: Int,
        commandCapacity: Int,
        maxOperationsPerFrame: Int,
        maxFrameWorkNanos: Long,
        nanoTime: () -> Long,
    ) : this(driver, assertOwner, stateCapacity, commandCapacity, maxOperationsPerFrame, maxFrameWorkNanos, nanoTime, null)

    private class Batch(val operations: Array<ScheduledOperation?>) {
        var applied = 0
        var cursor = 0
        var remaining = operations.size
            private set

        fun remove(index: Int) {
            operations[index] = null
            remaining--
        }

        fun clear() {
            operations.fill(null)
            remaining = 0
        }
    }

    /**
     * Mutable value stored in the state queues.
     *
     * One entry object per slot is reused across coalescing submissions, so the common "the same
     * property is submitted again before the frame" path allocates nothing and keeps the slot's
     * original queue position. An entry is owned by exactly one queue at a time: [states] and
     * [frameStates] are distinct maps holding distinct entries, so mutating the queued entry can
     * never leak into the frame snapshot being drained.
     */
    private class StateEntry(
        var isValid: () -> Boolean,
        var action: () -> Unit,
    )

    /**
     * Reusable open-addressing group table. Group ids are assigned in insertion order, so
     * [keyAt] iterates groups in the same order as the previous LinkedHashMap implementation.
     * Only these index buffers are reused across frames; the state/command queues are separate.
     */
    private class GroupTable(
        initialCapacity: Int = 16,
        private val maxCapacity: Int = initialCapacity,
    ) {
        var keys: Array<Any?> = arrayOfNulls(initialCapacity)
        var ids: IntArray = IntArray(initialCapacity)
        var head: IntArray = IntArray(initialCapacity)
        var tail: IntArray = IntArray(initialCapacity)
        var groupKeys: Array<Any?> = arrayOfNulls(initialCapacity)
        var mark: BooleanArray = BooleanArray(initialCapacity)
        var size = 0
            private set
        private var capacity = initialCapacity
        private var mask = initialCapacity - 1

        fun clear() {
            keys.fill(null)
            groupKeys.fill(null, 0, size)
            // Defensive: orderedGroups resets the marks it sets, but a future early return must
            // not leave stale marks behind.
            mark.fill(false, 0, size)
            size = 0
        }

        fun groupId(key: Any): Int {
            var index = key.hashCode() and mask
            var probes = 0
            while (probes++ < capacity) {
                val existing = keys[index]
                if (existing == null) return -1
                if (existing == key) return ids[index] - 1
                index = (index + 1) and mask
            }
            return -1
        }

        fun groupIdOrInsert(key: Any): Int {
            var index = key.hashCode() and mask
            var probes = 0
            while (probes++ < capacity) {
                val existing = keys[index]
                if (existing == null) {
                    if (size * 4 >= capacity * 3) {
                        grow()
                        return groupIdOrInsert(key)
                    }
                    val id = size++
                    keys[index] = key
                    ids[index] = id + 1
                    groupKeys[id] = key
                    head[id] = -1
                    tail[id] = -1
                    return id
                }
                if (existing == key) return ids[index] - 1
                index = (index + 1) and mask
            }
            // Unreachable while the table stays below 75% load; fail closed rather than loop.
            throw IllegalStateException("group table is full")
        }

        fun keyAt(id: Int): Any = groupKeys[id]!!

        private fun grow() {
            val newCapacity = capacity * 2
            if (newCapacity <= 0 || newCapacity > maxCapacity) throw IllegalStateException("group table exceeded its bound")
            val oldKeys = keys
            val oldIds = ids
            val oldHead = head
            val oldTail = tail
            val oldGroupKeys = groupKeys
            val oldSize = size
            keys = arrayOfNulls(newCapacity)
            ids = IntArray(newCapacity)
            head = IntArray(newCapacity)
            tail = IntArray(newCapacity)
            groupKeys = arrayOfNulls(newCapacity)
            mark = BooleanArray(newCapacity)
            capacity = newCapacity
            mask = newCapacity - 1
            for (id in 0 until oldSize) {
                head[id] = oldHead[id]
                tail[id] = oldTail[id]
                groupKeys[id] = oldGroupKeys[id]
            }
            for (index in oldKeys.indices) {
                val key = oldKeys[index] ?: continue
                var slot = key.hashCode() and mask
                while (keys[slot] != null) slot = (slot + 1) and mask
                keys[slot] = key
                ids[slot] = oldIds[index]
            }
        }
    }

    private class StateIndex(
        var slots: Array<Any?>,
        var next: IntArray,
        var groups: GroupTable,
    )

    private var states = linkedMapOf<Any, StateEntry>()
    private var driver: UiFrameDriver? = driver
    // Command queue entries are either a single ScheduledOperation (submitCommand fast path,
    // no Batch/array allocation) or a Batch (submitBatch).
    private var commands = ArrayDeque<Any>()
    private var frameStates: LinkedHashMap<Any, StateEntry>? = null
    private var frameCommands: ArrayDeque<Any>? = null
    private var frameGroups: ArrayDeque<Any>? = null
    private var frameIndex: StateIndex? = null
    private var spareIndex: StateIndex? = null
    private var spareStates: LinkedHashMap<Any, StateEntry>? = null
    private var spareCommands: ArrayDeque<Any>? = null
    private var spareGroupSet: MutableSet<Any>? = null
    private var spareGroupList: MutableList<Any>? = null
    // --- Asynchronous submission handoff ---------------------------------------------
    // Producers append here under `handoffLock` from any thread; the owner merges everything into
    // the queues in [mergeHandoff]. Keeping the two structures separate leaves the owner fast path
    // (submitState/submitCommand/applyState) completely lock-free.
    private val handoffLock = Any()
    private val handoffStates = linkedMapOf<Any, StateEntry>()
    private val handoffCommands = ArrayDeque<ScheduledOperation>()

    // Producer-side counters, guarded by `handoffLock` and folded into the owner-side counters on
    // the owner thread. Keeping them separate is what lets the owner fast path stay lock-free while
    // the plain Long counters are never written from two threads.
    /** Coalescing performed by producers; folded into [coalesced]/[released] on the owner. */
    private var handoffCoalesced = 0L
    /** Producer acceptances; folded into [accepted] on the owner. */
    private var handoffAccepted = 0L
    /** Producer-side rejections; folded into [rejected] on the owner. */
    private var handoffRejected = 0L
    private val handoffDispatchPending = AtomicBoolean(false)
    private val handoffDrain = Runnable { mergeHandoff() }
    private var nextStateGroups = ArrayDeque<Any>(stateCapacity)
    private var overlappingStates = 0
    private var pendingCommands = 0
    private var pendingTicket: FrameTicket? = null
    private var frameIdentity = 0L
    private var frameSequence = 0L
    private var requestingFrame = false
    private var draining = false
    private var preferState = true
    private var accepted = 0L
    private var coalesced = 0L
    private var applied = 0L
    private var stale = 0L
    private var released = 0L
    private var rejected = 0L
    private var requested = 0L
    private var drained = 0L

    val driverOwner: String = checkNotNull(driver).owner

    /** Volatile so the asynchronous entry points can read it from producer threads. */
    @Volatile
    var state: SchedulerState = SchedulerState.ACTIVE
        private set
    var failure: SchedulerFailure? = null
        private set
    var cancellationFailure: String? = null
        private set

    init {
        assertOwner()
        require(driverOwner.isNotBlank()) { "A frame driver must declare its owner" }
        require(stateCapacity > 0 && commandCapacity > 0)
        require(maxOperationsPerFrame > 0 && maxFrameWorkNanos > 0)
    }

    val pendingStateCount: Int
        get() {
            assertOwner()
            return states.size + (frameStates?.size ?: 0) - overlappingStates
        }

    val pendingCommandCount: Int
        get() {
            assertOwner()
            return pendingCommands
        }

    val counters: SchedulerCounters
        get() {
            assertOwner()
            return SchedulerCounters(accepted, coalesced, applied, stale, released, rejected, requested, drained)
        }

    fun submitState(slot: Any, isValid: () -> Boolean = { true }, action: () -> Unit): SubmitResult {
        assertOwner()
        rejectionForState()?.let { return reject(it) }
        val result = enqueueState(slot, isValid, action)
            ?: return reject(RejectionReason.STATE_CAPACITY)
        accepted++
        requestIfNeeded()
        return result
    }

    /**
     * Queues one state entry on the owner thread. Returns null when the queue is full for this new
     * slot, in which case nothing was queued; otherwise the usual [SubmitResult.Accepted] /
     * [SubmitResult.Coalesced] verdict.
     *
     * Does not touch [accepted]: the synchronous caller counts its own submission, while the
     * asynchronous merge is counted by the producer through [handoffAccepted].
     */
    private fun enqueueState(slot: Any, isValid: () -> Boolean, action: () -> Unit): SubmitResult? {
        // Coalescing fast path: the slot is already queued, so overwrite its entry in place. No
        // allocation, and the slot keeps the queue position that preserves group round-robin.
        val queued = states[slot]
        if (queued != null) {
            queued.isValid = isValid
            queued.action = action
            coalesced++
            released++
            return SubmitResult.Coalesced
        }
        // A brand-new slot is the only case that can hit the bound; an overlapping slot already
        // counted in pendingStateCount stays admissible.
        if (pendingStateCount >= stateCapacity && frameStates?.containsKey(slot) != true) return null
        states[slot] = StateEntry(isValid, action)
        if (frameStates?.containsKey(slot) == true) overlappingStates++
        return SubmitResult.Accepted
    }

    /**
     * Submits one same-semantic set of slot updates as a unit: a single owner check, a single frame
     * request and in-batch deduplication by slot.
     *
     * Admission is per entry and identical to calling [submitState] for each update in order, so a
     * batch that overruns [stateCapacity] leaves the earlier entries queued rather than rolling
     * back. The last update for a slot wins. Returns [SubmitResult.Coalesced] when every entry
     * replaced an already-queued slot, and [SubmitResult.Accepted] when at least one new slot was
     * queued.
     */
    fun submitStates(updates: List<StateUpdate>): SubmitResult {
        assertOwner()
        rejectionForState()?.let { return reject(it) }
        if (updates.isEmpty()) return reject(RejectionReason.EMPTY_BATCH)
        var queuedNewSlot = false
        for (update in updates) {
            val queued = states[update.slot]
            if (queued != null) {
                queued.isValid = update.isValid
                queued.action = update.action
                coalesced++
                released++
                accepted++
                continue
            }
            if (pendingStateCount >= stateCapacity && frameStates?.containsKey(update.slot) != true) {
                // Entries admitted before the rejection are already queued, so they still need a
                // frame; requestIfNeeded() is a no-op when one is already pending.
                requestIfNeeded()
                return reject(RejectionReason.STATE_CAPACITY)
            }
            states[update.slot] = StateEntry(update.isValid, update.action)
            if (frameStates?.containsKey(update.slot) == true) overlappingStates++
            accepted++
            queuedNewSlot = true
        }
        requestIfNeeded()
        return if (queuedNewSlot) SubmitResult.Accepted else SubmitResult.Coalesced
    }

    /**
     * Non-blocking state submission from any thread.
     *
     * The update is staged in a bounded handoff and merged into the owner queues on the owner
     * thread, where updates for the same slot posted in the meantime have already collapsed into a
     * single entry. One owner dispatch serves any number of staged updates, so a background producer
     * does not need its own `Handler.post` per update and does not cause extra frame requests.
     *
     * Rejection matches [submitState] for the scheduler-level conditions (PAUSED / FAULTED /
     * CLOSED, plus CLOSED when no [dispatcher] was supplied). Capacity is checked against the
     * handoff, whose bound is [stateCapacity]; an accepted update that finds the owner queue full at
     * merge time stays staged and is retried on a later frame — it is never dropped.
     */
    fun submitStateAsync(slot: Any, isValid: () -> Boolean = { true }, action: () -> Unit): SubmitResult {
        val dispatcher = dispatcher ?: return handoffReject(RejectionReason.CLOSED)
        var coalesced = false
        synchronized(handoffLock) {
            rejectionForState()?.let { return handoffReject(it) }
            val existing = handoffStates[slot]
            if (existing != null) {
                // Same in-place reuse as the owner fast path: a burst for one hot slot allocates
                // nothing, and the slot keeps its staged position.
                existing.isValid = isValid
                existing.action = action
                handoffCoalesced++
                coalesced = true
            } else {
                if (handoffStates.size >= stateCapacity) {
                    return handoffReject(RejectionReason.STATE_CAPACITY)
                }
                handoffStates[slot] = StateEntry(isValid, action)
            }
            handoffAccepted++
        }
        if (!scheduleHandoffDispatch(dispatcher)) {
            abandonHandoff()
            return handoffReject(RejectionReason.CLOSED)
        }
        return if (coalesced) SubmitResult.Coalesced else SubmitResult.Accepted
    }

    /** Non-blocking command submission from any thread; see [submitStateAsync]. */
    fun submitCommandAsync(isValid: () -> Boolean = { true }, action: () -> Unit): SubmitResult {
        val dispatcher = dispatcher ?: return handoffReject(RejectionReason.CLOSED)
        synchronized(handoffLock) {
            rejectionForState()?.let { return handoffReject(it) }
            if (handoffCommands.size >= commandCapacity) {
                return handoffReject(RejectionReason.COMMAND_CAPACITY)
            }
            handoffCommands.addLast(ScheduledOperation("command", isValid, action))
            handoffAccepted++
        }
        if (!scheduleHandoffDispatch(dispatcher)) {
            abandonHandoff()
            return handoffReject(RejectionReason.CLOSED)
        }
        return SubmitResult.Accepted
    }

    /**
     * Counts a producer-side rejection under [handoffLock]. The owner-side [reject] counter is never
     * written from a producer thread, so the two can never race.
     */
    private fun handoffReject(reason: RejectionReason): SubmitResult.Rejected {
        handoffRejected++
        return SubmitResult.Rejected(reason)
    }

    /**
     * Posts [handoffDrain] once for any number of pending updates. Returns false when the owner
     * refused the dispatch, leaving the handoff untouched for the caller to unwind.
     */
    private fun scheduleHandoffDispatch(dispatcher: OwnerThreadDispatcher): Boolean {
        if (!handoffDispatchPending.compareAndSet(false, true)) return true
        if (dispatcher.post(handoffDrain)) return true
        handoffDispatchPending.set(false)
        return false
    }

    /**
     * Producer side. Unwinds a refused dispatch: the submission that triggered it is voided (it
     * must not count as accepted, matching the synchronous rejection path) and the handoff is
     * dropped so the verdict and the queue state agree.
     *
     * Deliberately does not fold the producer counters into the owner-side ones: those are only
     * ever written on the owner thread, so this teardown path loses its diagnostics rather than
     * race. Callers hold no lock.
     */
    private fun abandonHandoff() {
        synchronized(handoffLock) {
            if (handoffAccepted > 0L) handoffAccepted--
            handoffStates.clear()
            handoffCommands.clear()
            handoffCoalesced = 0L
            handoffAccepted = 0L
            handoffRejected = 0L
            handoffDispatchPending.set(false)
        }
    }

    /**
     * Owner thread only. Discards everything staged and folds the producer counters in, exactly
     * like the owner queues are cleared.
     */
    private fun dropHandoff() {
        synchronized(handoffLock) {
            released += handoffStates.size + handoffCommands.size + handoffCoalesced
            accepted += handoffAccepted
            rejected += handoffRejected
            handoffStates.clear()
            handoffCommands.clear()
            handoffCoalesced = 0L
            handoffAccepted = 0L
            handoffRejected = 0L
        }
    }

    /** Moves the producer-side counters into the owner-side ones. Caller holds [handoffLock]. */
    private fun foldHandoffCounters() {
        if (handoffAccepted > 0L) { accepted += handoffAccepted; handoffAccepted = 0L }
        if (handoffRejected > 0L) { rejected += handoffRejected; handoffRejected = 0L }
        if (handoffCoalesced > 0L) {
            coalesced += handoffCoalesced
            released += handoffCoalesced
            handoffCoalesced = 0L
        }
    }

    /**
     * Owner thread only. Merges the handoff into the owner queues using the same rules as
     * [submitState]/[submitCommand]. Updates that do not fit stay staged: a queue at capacity
     * always has work pending, so a frame is coming and a later merge will find room.
     *
     * While the scheduler is not ACTIVE the handoff is left untouched: [resume] and [recover] merge
     * it, and [close] clears it. That keeps a paused/faulted/closed scheduler from accepting work
     * the synchronous entry points would have rejected.
     */
    private fun mergeHandoff() {
        handoffDispatchPending.set(false)
        assertOwner()
        var mergedAny = false
        synchronized(handoffLock) {
            if (state != SchedulerState.ACTIVE) return
            if (handoffStates.isEmpty() && handoffCommands.isEmpty()) {
                foldHandoffCounters()
                return
            }
            val stagedStates = handoffStates.entries.toList()
            handoffStates.clear()
            val stagedCommands = handoffCommands.toList()
            handoffCommands.clear()
            for ((slot, entry) in stagedStates) {
                if (enqueueState(slot, entry.isValid, entry.action) == null) handoffStates[slot] = entry
            }
            for (operation in stagedCommands) {
                if (pendingCommands >= commandCapacity) {
                    handoffCommands.addLast(operation)
                    continue
                }
                commands.addLast(operation)
                pendingCommands++
            }
            foldHandoffCounters()
            mergedAny = true
        }
        if (mergedAny) requestIfNeeded()
    }

    fun submitCommand(isValid: () -> Boolean = { true }, action: () -> Unit): SubmitResult {
        assertOwner()
        rejectionForState()?.let { return reject(it) }
        return enqueueCommand(isValid, action) ?: reject(RejectionReason.COMMAND_CAPACITY)
    }

    /** Owner-thread counterpart of [enqueueState]; null means the command queue is full. */
    private fun enqueueCommand(isValid: () -> Boolean, action: () -> Unit): SubmitResult? {
        if (pendingCommands >= commandCapacity) return null
        return enqueue(ScheduledOperation("command", isValid, action))
    }

    /** Entire batch admission is atomic; applying the admitted batch is deliberately not a transaction. */
    fun submitBatch(operations: List<ScheduledOperation>): SubmitResult {
        assertOwner()
        rejectionForState()?.let { return reject(it) }
        if (operations.isEmpty()) return reject(RejectionReason.EMPTY_BATCH)
        if (operations.size > commandCapacity - pendingCommands) return reject(RejectionReason.COMMAND_CAPACITY)
        return enqueue(Batch(operations.toTypedArray<ScheduledOperation?>()))
    }

    /** Convenience overload with the same admission, snapshot and FIFO contract as the list form. */
    fun submitBatch(vararg operations: ScheduledOperation): SubmitResult {
        assertOwner()
        rejectionForState()?.let { return reject(it) }
        if (operations.isEmpty()) return reject(RejectionReason.EMPTY_BATCH)
        if (operations.size > commandCapacity - pendingCommands) return reject(RejectionReason.COMMAND_CAPACITY)
        val snapshot = arrayOfNulls<ScheduledOperation>(operations.size)
        operations.copyInto(snapshot)
        return enqueue(Batch(snapshot))
    }

    private fun enqueue(entry: Any): SubmitResult {
        commands.addLast(entry)
        pendingCommands += if (entry is Batch) entry.remaining else 1
        accepted++
        requestIfNeeded()
        return SubmitResult.Accepted
    }

    /** Pause rejects new submissions. Default detach behavior also releases queued closures. */
    fun pause(clearPending: Boolean = true) {
        assertOwner()
        if (state == SchedulerState.CLOSED || state == SchedulerState.FAULTED) return
        state = SchedulerState.PAUSED
        // A driver cancel that raises an Error must not skip the queue cleanup.
        try { cancelFrame() } finally { if (clearPending) clearQueues() }
    }

    fun resume() {
        assertOwner()
        check(state == SchedulerState.PAUSED) { "Only a paused scheduler can resume" }
        state = SchedulerState.ACTIVE
        // Work staged before the pause was deliberately left queued; bring it in now.
        mergeHandoff()
        requestIfNeeded()
    }

    /** Caller must reconcile or rebuild actual state first. Failed operations are never retried. */
    fun recover() {
        assertOwner()
        check(state == SchedulerState.FAULTED) { "Only a faulted scheduler can recover" }
        failure = null
        state = SchedulerState.ACTIVE
        mergeHandoff()
    }

    /** Physically releases invalid pending work after rebind/release, without executing actions. */
    fun discardInvalid() {
        assertOwner()
        if (state == SchedulerState.CLOSED || state == SchedulerState.FAULTED) return
        var removedAny = false
        val stateQueues = listOfNotNull(states, frameStates)
        for (queue in stateQueues) {
            // Key snapshot, not a map copy: a validator may re-enter the scheduler, so the queue
            // must not be iterated directly. Keys cost one reference each; a toMap() copy would
            // allocate a full entry per slot on every rebind/release.
            for (slot in queue.keys.toList()) {
                if (state == SchedulerState.CLOSED || state == SchedulerState.FAULTED) return
                val entry = queue[slot] ?: continue
                val valid = checkPending("state", entry.isValid, 0, 0) ?: return
                if (!valid && queue[slot] === entry) {
                    val other = if (queue === states) frameStates else states
                    if (other?.containsKey(slot) == true) overlappingStates--
                    queue.remove(slot)
                    removedAny = true
                    stale++
                    released++
                }
            }
        }
        for (queue in listOfNotNull(commands, frameCommands)) {
            for (entry in queue.toList()) {
                if (entry is Batch) {
                    for (index in entry.cursor until entry.operations.size) {
                        if (state == SchedulerState.CLOSED || state == SchedulerState.FAULTED) return
                        val operation = entry.operations[index] ?: continue
                        val valid = checkPending(operation.label, operation.isValid, index, entry.applied) ?: return
                        if (!valid && queue.contains(entry) && entry.operations[index] === operation) {
                            entry.remove(index)
                            pendingCommands--
                            removedAny = true
                            stale++
                            released++
                        }
                    }
                    if (entry.remaining == 0) queue.remove(entry)
                } else {
                    if (state == SchedulerState.CLOSED || state == SchedulerState.FAULTED) return
                    val operation = entry as? ScheduledOperation
                        ?: error("unexpected command entry: ${entry::class.java.name}")
                    val valid = checkPending(operation.label, operation.isValid, 0, 0) ?: return
                    if (!valid && queue.contains(entry)) {
                        queue.remove(entry)
                        pendingCommands--
                        removedAny = true
                        stale++
                        released++
                    }
                }
            }
        }
        // Group order only goes stale when a group loses its last slot. A pure rescan (the common
        // rebind/release case) keeps every group, so rebuilding the set would be pure allocation.
        if (removedAny) {
            val groups = (states.keys + (frameStates?.keys ?: emptySet())).map(::stateGroup).toSet()
            nextStateGroups.removeAll { it !in groups }
            frameGroups?.removeAll { it !in groups }
        }
        if (pendingStateCount == 0 && pendingCommandCount == 0) cancelFrame()
    }

    private fun checkPending(label: String, isValid: () -> Boolean, index: Int, appliedInBatch: Int): Boolean? = try {
        isValid()
    } catch (error: Throwable) {
        fault(label, index, appliedInBatch, "validation", error)
        if (error is Error) throw error
        null
    }

    override fun close() {
        assertOwner()
        if (state == SchedulerState.CLOSED) return
        state = SchedulerState.CLOSED
        try { cancelFrame() } finally {
            clearQueues()
            driver = null
        }
    }

    private fun rejectionForState(): RejectionReason? = when (state) {
        SchedulerState.ACTIVE -> null
        SchedulerState.PAUSED -> RejectionReason.PAUSED
        SchedulerState.FAULTED -> RejectionReason.FAULTED
        SchedulerState.CLOSED -> RejectionReason.CLOSED
    }

    private fun reject(reason: RejectionReason): SubmitResult.Rejected {
        rejected++
        return SubmitResult.Rejected(reason)
    }

    private fun requestIfNeeded() {
        if (state != SchedulerState.ACTIVE || draining || frameIdentity != 0L ||
            (states.isEmpty() && commands.isEmpty())) return
        // A monotonically increasing serial avoids allocating an identity object per frame.
        val identity = ++frameSequence
        frameIdentity = identity
        requestingFrame = true
        requested++
        try {
            val ticket = checkNotNull(driver).requestFrame { budget ->
                assertOwner()
                check(!requestingFrame) { "UiFrameDriver must not invoke a requested callback synchronously" }
                if (frameIdentity != identity || state != SchedulerState.ACTIVE) return@requestFrame
                frameIdentity = 0L
                pendingTicket = null
                drain(budget)
            }
            if (frameIdentity == identity) pendingTicket = ticket else ticket.cancel()
        } catch (error: Throwable) {
            fault("frame-request", -1, 0, "driver", error)
            if (error is Error) throw error
        } finally {
            requestingFrame = false
        }
    }

    private fun drain(budget: FrameBudget) {
        check(!draining) { "Nested frame delivery is not supported" }
        // Staged background work joins this frame instead of waiting for another dispatch.
        mergeHandoff()
        draining = true
        drained++
        frameStates = states
        frameCommands = commands
        // A command-only frame has no snapshot to index; skip the state index entirely.
        val index = if (states.isEmpty()) null else indexStates(states)
        frameIndex = index
        val previousGroups = nextStateGroups
        val ordered = if (index == null) {
            previousGroups.clear()
            previousGroups
        } else {
            orderedGroups(previousGroups, index)
        }
        frameGroups = ordered
        val incomingStates = spareStates ?: linkedMapOf()
        spareStates = null
        val incomingCommands = spareCommands ?: ArrayDeque()
        spareCommands = null
        states = incomingStates
        commands = incomingCommands
        val started = nanoTime()
        // A real platform deadline caps the work budget by the time left before it, minus a tail
        // reserved for traversal/draw. An estimated or unknown deadline keeps the fixed budget.
        val reservedTailNanos = 1_000_000L // traversal/draw tail; see docs/scheduling.md
        val deadline = if (budget.estimated) null else budget.deadlineNanos
        val workBudgetNanos = if (deadline == null) maxFrameWorkNanos
        else (deadline - started - reservedTailNanos).coerceIn(0L, maxFrameWorkNanos)
        var operationCount = 0
        try {
            while (state == SchedulerState.ACTIVE && operationCount < maxOperationsPerFrame) {
                val now = nanoTime()
                if (now - started >= workBudgetNanos) break
                val stateWork = frameStates!!.isNotEmpty()
                val commandWork = frameCommands!!.isNotEmpty()
                if (!stateWork && !commandWork) break
                val takeState = stateWork && (!commandWork || preferState)
                preferState = !takeState
                if (takeState) applyState() else applyCommand()
                operationCount++
            }
        } finally {
            if (state == SchedulerState.ACTIVE || state == SchedulerState.PAUSED) {
                val remainingStates = frameStates!!
                incomingStates.forEach { (key, operation) ->
                    if (remainingStates.put(key, operation) != null) {
                        coalesced++
                        released++
                    }
                }
                states = remainingStates
                // Reuse one scratch set across frames; orderedGroups consumes it, so it is
                // cleared on the next frame before being filled again.
                val remainingGroups = spareGroupSet ?: linkedSetOf()
                spareGroupSet = null
                remainingGroups.clear()
                // Guard the iteration: keySet().iterator() allocates even when the map is empty.
                if (states.isNotEmpty()) states.keys.mapTo(remainingGroups, ::stateGroup)
                nextStateGroups = orderedGroups(frameGroups!!, remainingGroups)
                spareGroupSet = remainingGroups
                val remainingCommands = frameCommands!!
                remainingCommands.addAll(incomingCommands)
                commands = remainingCommands
            } else {
                states = frameStates ?: linkedMapOf()
                commands = frameCommands ?: ArrayDeque()
            }
            incomingStates.clear()
            incomingCommands.clear()
            spareStates = incomingStates
            spareCommands = incomingCommands
            if (index != null) {
                // Drop object references; only the primitive capacities are retained.
                index.slots.fill(null)
                index.groups.clear()
                spareIndex = index
            }
            frameStates = null
            frameCommands = null
            frameGroups = null
            frameIndex = null
            overlappingStates = 0
            draining = false
            requestIfNeeded()
        }
    }

    private fun applyState() {
        val queue = frameStates!!
        val groups = frameGroups!!
        while (groups.isNotEmpty()) {
            val group = groups.removeFirst()
            val index = frameIndex ?: return
            val id = index.groups.groupId(group)
            if (id < 0) continue
            var head = index.groups.head[id]
            while (head >= 0 && !queue.containsKey(index.slots[head]!!)) head = index.next[head]
            if (head < 0) continue
            val slot = index.slots[head]!!
            index.groups.head[id] = index.next[head]
            val entry = queue.remove(slot)!!
            if (states.containsKey(slot)) overlappingStates--
            head = index.groups.head[id]
            while (head >= 0 && !queue.containsKey(index.slots[head]!!)) head = index.next[head]
            if (head >= 0) groups.addLast(group)
            apply("state", entry.isValid, entry.action, 0, 0)
            return
        }
    }

    private fun stateGroup(slot: Any): Any = if (slot is StateSlot) slot.binding else slot

    /** Smallest power-of-two capacity that keeps the table below 75% load for [stateCapacity] groups. */
    private fun groupTableBound(): Int {
        val required = (stateCapacity.toLong() + stateCapacity / 3 + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        var bound = 16
        while (bound < required && bound < (1 shl 30)) bound = bound shl 1
        return bound
    }

    private fun indexStates(queue: Map<Any, StateEntry>): StateIndex {
        val index = spareIndex ?: StateIndex(emptyArray(), IntArray(0), GroupTable(16, groupTableBound()))
        spareIndex = null
        val count = queue.size
        if (index.slots.size < count) {
            index.slots = arrayOfNulls(count)
            index.next = IntArray(count) { -1 }
        } else {
            index.next.fill(-1, 0, count)
            if (index.slots.size > count) index.slots.fill(null, count, index.slots.size)
        }
        index.groups.clear()
        var slotIndex = 0
        for (slot in queue.keys) {
            index.slots[slotIndex] = slot
            val group = stateGroup(slot)
            val id = index.groups.groupIdOrInsert(group)
            if (index.groups.head[id] < 0) index.groups.head[id] = slotIndex
            else index.next[index.groups.tail[id]] = slotIndex
            index.groups.tail[id] = slotIndex
            slotIndex++
        }
        return index
    }

    private fun orderedGroups(previous: ArrayDeque<Any>, index: StateIndex): ArrayDeque<Any> {
        val groupCount = index.groups.size
        if (groupCount == 0) {
            previous.clear()
            return previous
        }
        if (previous.isEmpty()) {
            for (id in 0 until groupCount) previous.addLast(index.groups.keyAt(id))
            return previous
        }
        // Rebuild in place: keep the surviving groups in order, then append new groups. A
        // reusable list avoids O(k*n) ArrayDeque.delete shifts when many groups drop at once.
        val marked = index.groups.mark
        val surviving = spareGroupList ?: arrayListOf()
        spareGroupList = null
        surviving.clear()
        for (group in previous) {
            val id = index.groups.groupId(group)
            if (id >= 0 && !marked[id]) {
                marked[id] = true
                surviving.add(group)
            }
        }
        previous.clear()
        previous.addAll(surviving)
        spareGroupList = surviving
        for (id in 0 until groupCount) {
            if (marked[id]) marked[id] = false else previous.addLast(index.groups.keyAt(id))
        }
        return previous
    }

    /**
     * Rebuilds [previous] in place and returns it. When [previous] is non-empty, [current] is
     * consumed as scratch and left holding only the groups absent from [previous]; the
     * [previous].isEmpty() early return does not consume it.
     */
    private fun orderedGroups(previous: ArrayDeque<Any>, current: MutableSet<Any>): ArrayDeque<Any> {
        if (current.isEmpty()) {
            previous.clear()
            return previous
        }
        if (previous.isEmpty()) {
            previous.addAll(current)
            return previous
        }
        // Rebuild in place: keep the previous order for surviving groups, then append new ones.
        val surviving = spareGroupList ?: arrayListOf()
        spareGroupList = null
        surviving.clear()
        for (group in previous) {
            if (current.remove(group)) surviving.add(group)
        }
        previous.clear()
        previous.addAll(surviving)
        previous.addAll(current)
        spareGroupList = surviving
        return previous
    }

    private fun applyCommand() {
        val queue = frameCommands!!
        val entry = queue.first
        if (entry is Batch) {
            while (entry.operations[entry.cursor] == null) entry.cursor++
            val index = entry.cursor++
            val operation = entry.operations[index]!!
            entry.remove(index)
            pendingCommands--
            if (entry.remaining == 0) queue.removeFirst()
            if (apply(operation.label, operation.isValid, operation.action, index, entry.applied)) entry.applied++
        } else {
            val operation = entry as? ScheduledOperation
                ?: error("unexpected command entry: ${entry::class.java.name}")
            queue.removeFirst()
            pendingCommands--
            apply(operation.label, operation.isValid, operation.action, 0, 0)
        }
    }

    private fun apply(label: String, isValid: () -> Boolean, action: () -> Unit, index: Int, appliedInBatch: Int): Boolean {
        var phase = "validation"
        return try {
            if (!isValid()) {
                stale++
                false
            } else if (state != SchedulerState.ACTIVE) {
                false
            } else {
                phase = "action"
                action()
                applied++
                true
            }
        } catch (error: Throwable) {
            fault(label, index, appliedInBatch, phase, error)
            if (error is Error) throw error
            false
        } finally {
            released++
        }
    }

    private fun fault(label: String, index: Int, appliedInBatch: Int, phase: String, error: Throwable) {
        failure = SchedulerFailure(label, index, appliedInBatch, phase, error.javaClass.name, error.message)
        if (state != SchedulerState.CLOSED) state = SchedulerState.FAULTED
        try { cancelFrame() } finally { clearQueues() }
    }

    private fun cancelFrame() {
        frameIdentity = 0L
        val ticket = pendingTicket
        pendingTicket = null
        try {
            ticket?.cancel()
        } catch (error: Exception) {
            cancellationFailure = "${error.javaClass.name}: ${error.message}"
        }
    }

    private fun clearQueues() {
        released += states.size + (frameStates?.size ?: 0) + pendingCommands
        dropHandoff()
        states.clear()
        commands.forEach { if (it is Batch) it.clear() }
        commands.clear()
        frameStates?.clear()
        frameCommands?.forEach { if (it is Batch) it.clear() }
        frameCommands?.clear()
        frameGroups?.clear()
        frameIndex = null
        nextStateGroups.clear()
        overlappingStates = 0
        pendingCommands = 0
        spareStates?.clear()
        spareStates = null
        spareCommands?.forEach { if (it is Batch) it.clear() }
        spareCommands?.clear()
        spareCommands = null
        spareIndex = null
        spareGroupSet?.clear()
        spareGroupSet = null
        spareGroupList?.clear()
        spareGroupList = null
    }
}
