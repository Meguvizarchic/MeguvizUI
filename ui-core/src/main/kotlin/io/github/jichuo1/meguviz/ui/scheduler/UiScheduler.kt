package io.github.jichuo1.meguviz.ui.scheduler

import java.util.ArrayDeque

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
 * Owner-thread-only bounded scheduler. States coalesce by slot; commands and batches use FIFO.
 * Channels have no total order. Reentrant work never enters the current frame snapshot.
 */
class UiScheduler(
    driver: UiFrameDriver,
    private val assertOwner: () -> Unit,
    val stateCapacity: Int = 256,
    val commandCapacity: Int = 256,
    val maxOperationsPerFrame: Int = 64,
    val maxFrameWorkNanos: Long = 2_000_000,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
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

    private var states = linkedMapOf<Any, ScheduledOperation>()
    private var driver: UiFrameDriver? = driver
    private var commands = ArrayDeque<Batch>()
    private var frameStates: LinkedHashMap<Any, ScheduledOperation>? = null
    private var frameCommands: ArrayDeque<Batch>? = null
    private var frameGroups: ArrayDeque<Any>? = null
    private var frameIndex: StateIndex? = null
    private var spareIndex: StateIndex? = null
    private var spareStates: LinkedHashMap<Any, ScheduledOperation>? = null
    private var spareCommands: ArrayDeque<Batch>? = null
    private var nextStateGroups = ArrayDeque<Any>()
    private var overlappingStates = 0
    private var pendingCommands = 0
    private var pendingTicket: FrameTicket? = null
    private var frameIdentity: Any? = null
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
        val replacing = states.containsKey(slot)
        if (!replacing && frameStates?.containsKey(slot) != true && pendingStateCount >= stateCapacity) {
            return reject(RejectionReason.STATE_CAPACITY)
        }
        val previous = states.put(slot, ScheduledOperation("state", isValid, action))
        if (previous == null && frameStates?.containsKey(slot) == true) overlappingStates++
        accepted++
        if (previous != null) {
            coalesced++
            released++
        }
        requestIfNeeded()
        return if (replacing) SubmitResult.Coalesced else SubmitResult.Accepted
    }

    fun submitCommand(isValid: () -> Boolean = { true }, action: () -> Unit): SubmitResult {
        assertOwner()
        rejectionForState()?.let { return reject(it) }
        if (pendingCommands >= commandCapacity) return reject(RejectionReason.COMMAND_CAPACITY)
        return enqueue(Batch(arrayOf(ScheduledOperation("command", isValid, action))))
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

    private fun enqueue(batch: Batch): SubmitResult {
        commands.addLast(batch)
        pendingCommands += batch.remaining
        accepted++
        requestIfNeeded()
        return SubmitResult.Accepted
    }

    /** Pause rejects new submissions. Default detach behavior also releases queued closures. */
    fun pause(clearPending: Boolean = true) {
        assertOwner()
        if (state == SchedulerState.CLOSED || state == SchedulerState.FAULTED) return
        state = SchedulerState.PAUSED
        cancelFrame()
        if (clearPending) clearQueues()
    }

    fun resume() {
        assertOwner()
        check(state == SchedulerState.PAUSED) { "Only a paused scheduler can resume" }
        state = SchedulerState.ACTIVE
        requestIfNeeded()
    }

    /** Caller must reconcile or rebuild actual state first. Failed operations are never retried. */
    fun recover() {
        assertOwner()
        check(state == SchedulerState.FAULTED) { "Only a faulted scheduler can recover" }
        failure = null
        state = SchedulerState.ACTIVE
    }

    /** Physically releases invalid pending work after rebind/release, without executing actions. */
    fun discardInvalid() {
        assertOwner()
        if (state == SchedulerState.CLOSED || state == SchedulerState.FAULTED) return
        val stateQueues = listOfNotNull(states, frameStates)
        for (queue in stateQueues) {
            for ((slot, operation) in queue.toMap()) {
                if (state == SchedulerState.CLOSED || state == SchedulerState.FAULTED) return
                val valid = checkPending(operation, 0, 0) ?: return
                if (!valid && queue[slot] === operation) {
                    val other = if (queue === states) frameStates else states
                    if (other?.containsKey(slot) == true) overlappingStates--
                    queue.remove(slot)
                    stale++
                    released++
                }
            }
        }
        for (queue in listOfNotNull(commands, frameCommands)) {
            for (batch in queue.toList()) {
                for (index in batch.cursor until batch.operations.size) {
                    if (state == SchedulerState.CLOSED || state == SchedulerState.FAULTED) return
                    val operation = batch.operations[index] ?: continue
                    val valid = checkPending(operation, index, batch.applied) ?: return
                    if (!valid && queue.contains(batch) && batch.operations[index] === operation) {
                        batch.remove(index)
                        pendingCommands--
                        stale++
                        released++
                    }
                }
                if (batch.remaining == 0) queue.remove(batch)
            }
        }
        val groups = (states.keys + (frameStates?.keys ?: emptySet())).map(::stateGroup).toSet()
        nextStateGroups.removeAll { it !in groups }
        frameGroups?.removeAll { it !in groups }
        if (pendingStateCount == 0 && pendingCommandCount == 0) cancelFrame()
    }

    private fun checkPending(operation: ScheduledOperation, index: Int, appliedInBatch: Int): Boolean? = try {
        operation.isValid()
    } catch (error: Throwable) {
        fault(operation.label, index, appliedInBatch, "validation", error)
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
        if (state != SchedulerState.ACTIVE || draining || frameIdentity != null ||
            (states.isEmpty() && commands.isEmpty())) return
        val identity = Any()
        frameIdentity = identity
        requestingFrame = true
        requested++
        try {
            val ticket = checkNotNull(driver).requestFrame { budget ->
                assertOwner()
                check(!requestingFrame) { "UiFrameDriver must not invoke a requested callback synchronously" }
                if (frameIdentity !== identity || state != SchedulerState.ACTIVE) return@requestFrame
                frameIdentity = null
                pendingTicket = null
                drain(budget)
            }
            if (frameIdentity === identity) pendingTicket = ticket else ticket.cancel()
        } catch (error: Throwable) {
            fault("frame-request", -1, 0, "driver", error)
            if (error is Error) throw error
        } finally {
            requestingFrame = false
        }
    }

    private fun drain(budget: FrameBudget) {
        check(!draining) { "Nested frame delivery is not supported" }
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
        if (ordered !== previousGroups) previousGroups.clear()
        val incomingStates = spareStates ?: linkedMapOf()
        spareStates = null
        val incomingCommands = spareCommands ?: ArrayDeque()
        spareCommands = null
        states = incomingStates
        commands = incomingCommands
        val started = nanoTime()
        var operationCount = 0
        try {
            while (state == SchedulerState.ACTIVE && operationCount < maxOperationsPerFrame) {
                val now = nanoTime()
                if (now - started >= maxFrameWorkNanos || budget.deadlineNanos?.let { now >= it } == true) break
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
                val remainingGroups = if (states.isEmpty()) emptySet() else states.keys.mapTo(linkedSetOf(), ::stateGroup)
                nextStateGroups = orderedGroups(frameGroups!!, remainingGroups)
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
            val operation = queue.remove(slot)!!
            if (states.containsKey(slot)) overlappingStates--
            head = index.groups.head[id]
            while (head >= 0 && !queue.containsKey(index.slots[head]!!)) head = index.next[head]
            if (head >= 0) groups.addLast(group)
            apply(operation, 0, 0)
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

    private fun indexStates(queue: Map<Any, ScheduledOperation>): StateIndex {
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
        val marked = index.groups.mark
        val ordered = ArrayDeque<Any>(groupCount)
        for (group in previous) {
            val id = index.groups.groupId(group)
            if (id >= 0 && !marked[id]) {
                marked[id] = true
                ordered.addLast(group)
            }
        }
        for (id in 0 until groupCount) {
            if (marked[id]) marked[id] = false else ordered.addLast(index.groups.keyAt(id))
        }
        return ordered
    }

    private fun orderedGroups(previous: ArrayDeque<Any>, current: Set<Any>): ArrayDeque<Any> {
        if (current.isEmpty()) {
            previous.clear()
            return previous
        }
        if (previous.isEmpty()) {
            previous.addAll(current)
            return previous
        }
        val remaining = current.toMutableSet()
        val ordered = ArrayDeque<Any>()
        for (group in previous) if (remaining.remove(group)) ordered.addLast(group)
        ordered.addAll(remaining)
        return ordered
    }

    private fun applyCommand() {
        val queue = frameCommands!!
        val batch = queue.first
        while (batch.operations[batch.cursor] == null) batch.cursor++
        val index = batch.cursor++
        val operation = batch.operations[index]!!
        batch.remove(index)
        pendingCommands--
        if (batch.remaining == 0) queue.removeFirst()
        if (apply(operation, index, batch.applied)) batch.applied++
    }

    private fun apply(operation: ScheduledOperation, index: Int, appliedInBatch: Int): Boolean {
        var phase = "validation"
        return try {
            if (!operation.isValid()) {
                stale++
                false
            } else if (state != SchedulerState.ACTIVE) {
                false
            } else {
                phase = "action"
                operation.action()
                applied++
                true
            }
        } catch (error: Throwable) {
            fault(operation.label, index, appliedInBatch, phase, error)
            if (error is Error) throw error
            false
        } finally {
            released++
        }
    }

    private fun fault(label: String, index: Int, appliedInBatch: Int, phase: String, error: Throwable) {
        failure = SchedulerFailure(label, index, appliedInBatch, phase, error.javaClass.name, error.message)
        if (state != SchedulerState.CLOSED) state = SchedulerState.FAULTED
        cancelFrame()
        clearQueues()
    }

    private fun cancelFrame() {
        frameIdentity = null
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
        states.clear()
        commands.forEach { it.clear() }
        commands.clear()
        frameStates?.clear()
        frameCommands?.forEach { it.clear() }
        frameCommands?.clear()
        frameGroups?.clear()
        frameIndex = null
        nextStateGroups.clear()
        overlappingStates = 0
        pendingCommands = 0
        spareStates?.clear()
        spareStates = null
        spareCommands?.forEach { it.clear() }
        spareCommands?.clear()
        spareCommands = null
        spareIndex = null
    }
}
