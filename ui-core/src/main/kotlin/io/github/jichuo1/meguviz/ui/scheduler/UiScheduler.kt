package io.github.jichuo1.meguviz.ui.scheduler

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

enum class SchedulerState { ACTIVE, PAUSED, FAULTED, CLOSED }

enum class RejectionReason { PAUSED, FAULTED, CLOSED, STATE_CAPACITY, COMMAND_CAPACITY, EMPTY_BATCH }

/** Stable owner and property identities provide round-robin fairness between active bindings. */
data class StateSlot(val binding: Any, val property: Any)

sealed class SubmitResult {
    data object Accepted : SubmitResult()
    data object Coalesced : SubmitResult()
    data class Rejected(val reason: RejectionReason) : SubmitResult()
}

/** An explicitly ordered operation; [isValid] is rechecked immediately before its action. */
class ScheduledOperation(
    val label: String = "operation",
    val isValid: () -> Boolean = { true },
    val action: () -> Unit,
)

data class SchedulerFailure(
    val operation: String,
    val operationIndex: Int,
    val appliedInBatch: Int,
    val phase: String,
    val exceptionType: String,
    val message: String?,
)

data class SchedulerCounters(
    val acceptedSubmissions: Long,
    val coalescedStates: Long,
    val appliedOperations: Long,
    val staleOperations: Long,
    val releasedOperations: Long,
    val rejectedSubmissions: Long,
    val requestedFrames: Long,
    val drainedFrames: Long,
)

/**
 * Non-blocking owner-thread entry point used by the asynchronous submission path.
 *
 * This is the same shape as `OwnerDispatcher` in MeguvizAndroid, redeclared here so ui-core stays
 * independent of the sibling library.
 */
fun interface OwnerThreadDispatcher {
    /**
     * Arranges for [callback] to run on the owner thread. Must not block. Returns false when the
     * owner refused the dispatch (for example its looper is quitting), in which case the callback
     * will not run.
     *
     * Takes a [Runnable] so this is shape-identical to `OwnerDispatcher.post` in MeguvizAndroid,
     * keeping the two independent libraries' contracts comparable.
     */
    fun post(callback: Runnable): Boolean
}

/**
 * Owner-thread-only bounded scheduler. States coalesce by slot; commands and batches use FIFO.
 * Channels have no total order. Reentrant work never enters the current frame snapshot.
 *
 * A [dispatcher] is optional: without one the scheduler stays owner-thread-only and the
 * `submit*Async` entry points report [RejectionReason.CLOSED]. With one, they stage work in a
 * bounded handoff and merge it on the owner thread, so any number of background updates costs one
 * owner dispatch and one frame request.
 */
class UiScheduler(
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
     * The pre-1.2 seven-argument constructor, kept so binaries built against 1.0.0 still link.
     *
     * It behaves exactly like the primary constructor with a null [dispatcher], i.e. owner-thread
     * only: the `submit*Async` entry points report [RejectionReason.CLOSED]. Source that used to
     * compile against the seven-argument form keeps compiling and keeps linking.
     *
     * The defaults must stay identical to the primary constructor's. They are declared here as
     * well on purpose: Kotlin call sites that omitted trailing arguments were compiled against a
     * synthetic `(…, int mask, DefaultConstructorMarker)` constructor, and that one is generated
     * from the defaults of a constructor with exactly these seven parameters. Without them such a
     * binary fails with `NoSuchMethodError`. Overload resolution prefers this constructor whenever
     * a call needs no `dispatcher`, which is the same behavior as the primary one.
     */
    constructor(
        driver: UiFrameDriver,
        assertOwner: () -> Unit,
        stateCapacity: Int = 256,
        commandCapacity: Int = 256,
        maxOperationsPerFrame: Int = 64,
        maxFrameWorkNanos: Long = 2_000_000,
        nanoTime: () -> Long = System::nanoTime,
    ) : this(driver, assertOwner, stateCapacity, commandCapacity, maxOperationsPerFrame, maxFrameWorkNanos, nanoTime, null)

    /**
     * A single queued command. Always created by the scheduler itself (never a caller object), so
     * one instance is queued at most once and [queued] is an exact "still in a command queue"
     * marker: it is cleared on every path that takes the entry out (apply, discard, clear).
     *
     * [discardInvalid] relies on it instead of `ArrayDeque.contains`, which was O(C) per stale
     * command. Like the old `contains`, the flag is visible across nested scheduler calls made
     * from a validator, so a reentrant `close()`/`pause()`/`discardInvalid()` that already took the
     * entry out keeps the outer sweep from counting it twice.
     */
    private class CommandEntry(val isValid: () -> Boolean, val action: () -> Unit) {
        var queued = true
    }

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
    ) {
        /** Incremented on every producer coalesce; a refused submission only rolls back its own revision. */
        var revision: Long = 0L
    }

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
    // Command queue entries are either a single CommandEntry (submitCommand fast path, no
    // Batch/array allocation) or a Batch (submitBatch).
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
    // Swapped (never reallocated) by mergeHandoff's fast path; only ever touched under the lock
    // while installed here. The spares are owner-only and always empty between merges.
    private var handoffStates = linkedMapOf<Any, StateEntry>()
    private var handoffCommands = ArrayDeque<CommandEntry>()
    private var spareHandoffStates = linkedMapOf<Any, StateEntry>()
    private var spareHandoffCommands = ArrayDeque<CommandEntry>()

    // Producer-side counters, guarded by `handoffLock` and folded into the owner-side counters on
    // the owner thread. Keeping them separate is what lets the owner fast path stay lock-free while
    // the plain Long counters are never written from two threads.
    /** Coalescing performed by producers; folded into [coalesced]/[released] on the owner. */
    private var handoffCoalesced = 0L
    /** Producer acceptances; folded into [accepted] on the owner. */
    private var handoffAccepted = 0L
    /** Producer-side rejections; folded into [rejected] on the owner. */
    private var handoffRejected = 0L
    /**
     * Guarded by `handoffLock`; incremented each time [mergeHandoff] takes staged entries. A producer
     * records the value it saw when it staged. If the value differs when its dispatch turns out to
     * be refused, the owner has already taken the entry (the fast path moves the entry object into
     * the owner queue, where it may even have run), so the submission can no longer be rolled back
     * and is reported as accepted instead of rejected-but-executed.
     */
    private var handoffMergeEpoch = 0L
    private val handoffDispatchPending = AtomicBoolean(false)
    private val handoffDrain = Runnable { mergeHandoff() }
    private var nextStateGroups = ArrayDeque<Any>(stateCapacity)
    private var overlappingStates = 0
    private var pendingCommands = 0
    private var pendingTicket: FrameTicket? = null
    private var frameIdentity = 0L
    private var frameSequence = 0L
    private var requestingFrame = false
    /**
     * Number of [discardInvalid] command sweeps on the stack. While non-zero the command deques may
     * still hold released entries awaiting compaction; [drain] compacts before snapshotting so a
     * frame delivered from inside a validator never sees them.
     */
    private var commandSweeps = 0
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
    private fun enqueueState(slot: Any, isValid: () -> Boolean, action: () -> Unit): SubmitResult? =
        enqueueState(slot, isValid, action, null)

    /** As above, but a new slot adopts [staged] instead of allocating; [staged] must be unowned. */
    private fun enqueueState(slot: Any, staged: StateEntry): SubmitResult? =
        enqueueState(slot, staged.isValid, staged.action, staged)

    private fun enqueueState(slot: Any, isValid: () -> Boolean, action: () -> Unit, staged: StateEntry?): SubmitResult? {
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
        states[slot] = staged ?: StateEntry(isValid, action)
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
        var rollback: (() -> Unit)? = null
        var epoch = 0L
        synchronized(handoffLock) {
            rejectionForState()?.let { return handoffReject(it) }
            val existing = handoffStates[slot]
            if (existing != null) {
                // Same in-place reuse as the owner fast path: a burst for one hot slot allocates
                // nothing, and the slot keeps its staged position.
                val previousIsValid = existing.isValid
                val previousAction = existing.action
                val revision = ++existing.revision
                existing.isValid = isValid
                existing.action = action
                handoffCoalesced++
                coalesced = true
                rollback = {
                    // Another producer may have coalesced into this entry while `post` was in
                    // flight; restoring the old value then would erase its accepted update.
                    if (existing.revision == revision) {
                        existing.isValid = previousIsValid
                        existing.action = previousAction
                    }
                    if (handoffCoalesced > 0L) handoffCoalesced--
                }
            } else {
                if (handoffStates.size >= stateCapacity) {
                    return handoffReject(RejectionReason.STATE_CAPACITY)
                }
                val entry = StateEntry(isValid, action)
                entry.revision = 1L
                handoffStates[slot] = entry
                val revision = entry.revision
                // A later producer may coalesce into the very entry this call created. Removing the
                // slot unconditionally on rollback would then also erase its accepted value.
                rollback = {
                    if (handoffStates[slot] === entry && entry.revision == revision) {
                        handoffStates.remove(slot)
                    }
                }
            }
            handoffAccepted++
            epoch = handoffMergeEpoch
        }
        val verdict = if (coalesced) SubmitResult.Coalesced else SubmitResult.Accepted
        if (!scheduleHandoffDispatch(dispatcher)) {
            return unwindRefusedDispatch(checkNotNull(rollback), dispatcher, epoch, verdict)
        }
        return verdict
    }

    /** Non-blocking command submission from any thread; see [submitStateAsync]. */
    fun submitCommandAsync(isValid: () -> Boolean = { true }, action: () -> Unit): SubmitResult {
        val dispatcher = dispatcher ?: return handoffReject(RejectionReason.CLOSED)
        var rollback: (() -> Unit)? = null
        var epoch = 0L
        synchronized(handoffLock) {
            rejectionForState()?.let { return handoffReject(it) }
            if (handoffCommands.size >= commandCapacity) {
                return handoffReject(RejectionReason.COMMAND_CAPACITY)
            }
            val entry = CommandEntry(isValid, action)
            handoffCommands.addLast(entry)
            rollback = { handoffCommands.remove(entry) }
            handoffAccepted++
            epoch = handoffMergeEpoch
        }
        if (!scheduleHandoffDispatch(dispatcher)) {
            return unwindRefusedDispatch(checkNotNull(rollback), dispatcher, epoch, SubmitResult.Accepted)
        }
        return SubmitResult.Accepted
    }

    /**
     * Counts a producer-side rejection under [handoffLock]. The owner-side [reject] counter is never
     * written from a producer thread, so the two can never race. Callers may already hold the lock:
     * JVM monitors are reentrant.
     */
    private fun handoffReject(reason: RejectionReason): SubmitResult.Rejected {
        synchronized(handoffLock) { handoffRejected++ }
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
     * Producer side. Rolls back **only** this submission: another producer may have staged work
     * while `post` was in flight and has already been told "Accepted". Diagnostics are adjusted
     * incrementally, so other producers' acceptances, coalescences and rejections are preserved.
     *
     * The failed `post` cleared [handoffDispatchPending], so a replacement dispatch is requested
     * for whatever remains staged. If that post is refused too, the staged entries are kept rather
     * than silently discarded: the next successful post, or the next owner frame ([drain] calls
     * [mergeHandoff]), merges them. There is deliberately no permanent terminal flag: a dispatcher
     * may recover, and a hidden state that silently turns every later async submission into CLOSED
     * would be unobservable to callers.
     *
     * If the owner merged the handoff after this submission was staged ([handoffMergeEpoch] moved),
     * the entry now lives in the owner queue and may already have run: it can neither be taken back
     * nor safely mutated from this thread. The refusal is then moot (the owner is demonstrably
     * draining) and the submission keeps [accepted] as its verdict; nothing is rolled back.
     */
    private fun unwindRefusedDispatch(
        rollback: () -> Unit,
        dispatcher: OwnerThreadDispatcher,
        epoch: Long,
        accepted: SubmitResult,
    ): SubmitResult {
        val merged: Boolean
        val hasRemaining: Boolean
        synchronized(handoffLock) {
            merged = handoffMergeEpoch != epoch
            if (!merged) {
                rollback()
                if (handoffAccepted > 0L) handoffAccepted--
                handoffRejected++
            }
            hasRemaining = handoffStates.isNotEmpty() || handoffCommands.isNotEmpty()
        }
        if (hasRemaining) scheduleHandoffDispatch(dispatcher)
        return if (merged) accepted else SubmitResult.Rejected(RejectionReason.CLOSED)
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
        val stagedStates: LinkedHashMap<Any, StateEntry>
        val stagedCommands: ArrayDeque<CommandEntry>
        synchronized(handoffLock) {
            if (state != SchedulerState.ACTIVE) return
            if (handoffStates.isEmpty() && handoffCommands.isEmpty()) {
                foldHandoffCounters()
                return
            }
            // From here on the staged entries belong to the owner (or are re-staged as leftovers
            // that the owner is responsible for): producers that staged earlier can no longer roll back.
            handoffMergeEpoch++
            if (pendingStateCount + handoffStates.size > stateCapacity ||
                pendingCommands + handoffCommands.size > commandCapacity) {
                // Something may not fit: keep the exact original merge under the lock, so the
                // leftovers go back ahead of anything a producer stages afterwards and keep
                // counting against the handoff bound.
                mergeHandoffLocked()
                stagedStates = spareHandoffStates
                stagedCommands = spareHandoffCommands
            } else {
                // Fast path: everything is guaranteed to fit (each staged state adds at most one
                // pending slot, each command exactly one), so no entry can come back. Swap the
                // buffers and merge outside the lock: the producer-blocking window is O(1)
                // instead of O(staged), and nothing is copied.
                stagedStates = handoffStates
                stagedCommands = handoffCommands
                handoffStates = spareHandoffStates
                handoffCommands = spareHandoffCommands
                foldHandoffCounters()
            }
        }
        try {
            if (stagedStates.isNotEmpty()) {
                for ((slot, entry) in stagedStates) {
                    // The staged entry object itself moves into the owner queue (no copy); the
                    // staged map is cleared below, so the entry stays owned by exactly one queue.
                    checkNotNull(enqueueState(slot, entry)) { "handoff fast path admitted beyond capacity" }
                }
                stagedStates.clear()
            }
            while (stagedCommands.isNotEmpty()) {
                commands.addLast(stagedCommands.pollFirst())
                pendingCommands++
            }
        } finally {
            // Always return *empty* buffers to the spare pair. If an invariant check throws after
            // the fast-path swap, `handoffStates` and `spareHandoffStates` would otherwise stay
            // aliased forever and every later swap would hand live entries to the spare side.
            stagedStates.clear()
            stagedCommands.clear()
            spareHandoffStates = stagedStates
            spareHandoffCommands = stagedCommands
            requestIfNeeded()
        }
    }

    /** The pre-swap merge, used when the owner queues may not take everything. Holds the lock. */
    private fun mergeHandoffLocked() {
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
    }

    fun submitCommand(isValid: () -> Boolean = { true }, action: () -> Unit): SubmitResult {
        assertOwner()
        rejectionForState()?.let { return reject(it) }
        return enqueueCommand(isValid, action) ?: reject(RejectionReason.COMMAND_CAPACITY)
    }

    /** Owner-thread counterpart of [enqueueState]; null means the command queue is full. */
    private fun enqueueCommand(isValid: () -> Boolean, action: () -> Unit): SubmitResult? {
        if (pendingCommands >= commandCapacity) return null
        return enqueue(CommandEntry(isValid, action))
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
        // Stale commands are released logically (CommandEntry.queued / Batch slot nulled) and the
        // queues are compacted once at the end, so C stale commands cost O(C) instead of the O(C^2)
        // of a linear ArrayDeque.contains/remove per entry. A nested call compacts too; this outer
        // sweep iterates its own snapshot, so that never disturbs it.
        var removedCommand = false
        commandSweeps++
        try {
            for (queue in listOfNotNull(commands, frameCommands)) {
                // Snapshot only entries still queued: the legacy sweep copied the deque after
                // enclosing sweeps had physically removed their stale entries, so a released entry
                // awaiting compaction must not have its validator run again. Released entries can
                // only exist while an enclosing sweep is on the stack (every sweep compacts on
                // exit), so the outermost sweep keeps the plain array copy.
                val snapshot = if (commandSweeps > 1) liveSnapshot(queue) else queue.toList()
                for (entry in snapshot) {
                    if (entry is Batch) {
                        for (index in entry.cursor until entry.operations.size) {
                            if (state == SchedulerState.CLOSED || state == SchedulerState.FAULTED) return
                            val operation = entry.operations[index] ?: continue
                            val valid = checkPending(operation.label, operation.isValid, index, entry.applied) ?: return
                            // A batch leaves a queue only once every slot is null (applied, discarded
                            // or cleared), so the slot identity check alone says "still queued".
                            if (!valid && entry.operations[index] === operation) {
                                entry.remove(index)
                                pendingCommands--
                                removedAny = true
                                removedCommand = true
                                stale++
                                released++
                            }
                        }
                        if (entry.remaining == 0) removedCommand = true
                    } else {
                        if (state == SchedulerState.CLOSED || state == SchedulerState.FAULTED) return
                        val command = entry as? CommandEntry
                            ?: error("unexpected command entry: ${entry::class.java.name}")
                        val valid = checkPending("command", command.isValid, 0, 0) ?: return
                        if (!valid && command.queued) {
                            command.queued = false
                            pendingCommands--
                            removedAny = true
                            removedCommand = true
                            stale++
                            released++
                        }
                    }
                }
            }
        } finally {
            commandSweeps--
            if (removedCommand) {
                compactCommands(commands)
                frameCommands?.let(::compactCommands)
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

    /** Exactly-sized copy of the entries still queued (same cost as the former `toList()`). */
    private fun liveSnapshot(queue: ArrayDeque<Any>): ArrayList<Any> {
        val snapshot = ArrayList<Any>(queue.size)
        for (entry in queue) if (entry is Batch || (entry as CommandEntry).queued) snapshot.add(entry)
        return snapshot
    }

    /** One O(n) pass that physically drops released commands and emptied batches, keeping order. */
    private fun compactCommands(queue: ArrayDeque<Any>) {
        repeat(queue.size) {
            val entry = queue.pollFirst()
            val live = if (entry is Batch) entry.remaining > 0 else (entry as CommandEntry).queued
            if (live) queue.addLast(entry)
        }
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
        // pendingCommands rather than commands.isEmpty(): outside a drain they agree, except that a
        // discardInvalid sweep may still hold released entries awaiting compaction.
        if (state != SchedulerState.ACTIVE || draining || frameIdentity != 0L ||
            (states.isEmpty() && pendingCommands == 0)) return
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
        if (commandSweeps > 0) compactCommands(commands)
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
            val command = entry as? CommandEntry
                ?: error("unexpected command entry: ${entry::class.java.name}")
            queue.removeFirst()
            command.queued = false
            pendingCommands--
            apply("command", command.isValid, command.action, 0, 0)
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

    private fun releaseCommand(entry: Any) {
        if (entry is Batch) entry.clear() else (entry as CommandEntry).queued = false
    }

    private fun clearQueues() {
        released += states.size + (frameStates?.size ?: 0) + pendingCommands
        dropHandoff()
        states.clear()
        commands.forEach(::releaseCommand)
        commands.clear()
        frameStates?.clear()
        frameCommands?.forEach(::releaseCommand)
        frameCommands?.clear()
        frameGroups?.clear()
        frameIndex = null
        nextStateGroups.clear()
        overlappingStates = 0
        pendingCommands = 0
        spareStates?.clear()
        spareStates = null
        spareCommands?.forEach(::releaseCommand)
        spareCommands?.clear()
        spareCommands = null
        spareIndex = null
        spareGroupSet?.clear()
        spareGroupSet = null
        spareGroupList?.clear()
        spareGroupList = null
    }
}
