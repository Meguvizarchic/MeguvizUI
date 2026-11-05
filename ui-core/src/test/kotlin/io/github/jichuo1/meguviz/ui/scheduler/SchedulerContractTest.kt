package io.github.jichuo1.meguviz.ui.scheduler

import io.github.jichuo1.meguviz.ui.testkit.VirtualFrameDriver
import org.junit.Assert.*
import org.junit.Test

class SchedulerContractTest {
    private fun scheduler(
        driver: VirtualFrameDriver,
        stateCapacity: Int = 256,
        commandCapacity: Int = 256,
        maxOperations: Int = 64,
        clock: () -> Long = { 0L },
    ) = UiScheduler(driver, {}, stateCapacity, commandCapacity, maxOperations, nanoTime = clock)

    @Test fun mvxF01OnlyOneOutstandingFrameAndNoExtraQueueFrame() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver)
        val log = mutableListOf<String>()
        scheduler.submitState("title") { log += "title" }
        scheduler.submitCommand { log += "click" }
        assertEquals(1, driver.pendingCount)
        assertTrue(log.isEmpty())
        driver.tick()
        assertEquals(listOf("title", "click"), log)
        assertEquals(listOf("request:1", "tick:1"), driver.events)
        assertEquals(0, driver.pendingCount)
    }

    @Test fun mvxF02StateLatestDoesNotCollapseOrderedCommands() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver)
        val states = mutableListOf<Int>()
        val commands = mutableListOf<Int>()
        repeat(300) { value -> scheduler.submitState("title") { states += value } }
        scheduler.submitCommand { commands += 1 }
        scheduler.submitCommand { commands += 1 }
        scheduler.submitCommand { commands += 2 }
        driver.tick()
        assertEquals(listOf(299), states)
        assertEquals(listOf(1, 1, 2), commands)
        assertEquals(299L, scheduler.counters.coalescedStates)
        assertEquals(4L, scheduler.counters.appliedOperations)
        assertEquals(303L, scheduler.counters.releasedOperations)
    }

    @Test fun mvxF03OverloadRejectsNewSlotsButAllowsReplacement() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver, stateCapacity = 1, commandCapacity = 2)
        val log = mutableListOf<String>()
        assertEquals(SubmitResult.Accepted, scheduler.submitState("a") { log += "old" })
        assertEquals(SubmitResult.Coalesced, scheduler.submitState("a") { log += "new" })
        assertEquals(SubmitResult.Rejected(RejectionReason.STATE_CAPACITY), scheduler.submitState("b") {})
        scheduler.submitCommand { log += "one" }
        assertEquals(SubmitResult.Rejected(RejectionReason.COMMAND_CAPACITY), scheduler.submitBatch(listOf(
            ScheduledOperation { log += "never1" }, ScheduledOperation { log += "never2" },
        )))
        scheduler.submitCommand { log += "two" }
        driver.tick()
        assertEquals(listOf("new", "one", "two"), log)
    }

    @Test fun mvxF03ReentrantSubmissionsWaitForNextFrame() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver)
        val log = mutableListOf<String>()
        scheduler.submitCommand {
            log += "outer"
            scheduler.submitCommand { log += "inner" }
            scheduler.submitState("new") { log += "state" }
        }
        driver.tick()
        assertEquals(listOf("outer"), log)
        assertEquals(1, driver.pendingCount)
        driver.tick()
        assertEquals(setOf("outer", "inner", "state"), log.toSet())
        assertEquals(3, log.size)
    }

    @Test fun capacityIncludesUnprocessedFrameSnapshotDuringReentry() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver, commandCapacity = 2)
        var result: SubmitResult? = null
        scheduler.submitCommand {
            result = scheduler.submitBatch(listOf(ScheduledOperation {}, ScheduledOperation {}))
        }
        scheduler.submitCommand {}
        driver.tick()
        assertEquals(SubmitResult.Rejected(RejectionReason.COMMAND_CAPACITY), result)
    }

    @Test fun orderedBatchUsesFifoAcrossFramesAndRecordsPartialFailure() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver, maxOperations = 1)
        val log = mutableListOf<String>()
        scheduler.submitBatch(listOf(
            ScheduledOperation("set") { log += "set" },
            ScheduledOperation("focus") { log += "focus" },
            ScheduledOperation("fail") { error("setter rejected") },
            ScheduledOperation("after") { log += "after" },
        ))
        scheduler.submitCommand { log += "later" }
        driver.tick()
        driver.tick()
        driver.tick()
        assertEquals(listOf("set", "focus"), log)
        assertEquals(SchedulerState.FAULTED, scheduler.state)
        assertEquals(2, scheduler.failure!!.operationIndex)
        assertEquals(2, scheduler.failure!!.appliedInBatch)
        assertEquals("fail", scheduler.failure!!.operation)
        assertEquals(0, scheduler.pendingCommandCount)
        assertEquals(0, driver.pendingCount)
        assertEquals(SubmitResult.Rejected(RejectionReason.FAULTED), scheduler.submitCommand {})
        scheduler.recover()
        scheduler.submitCommand { log += "reconciled" }
        driver.tick()
        assertEquals(listOf("set", "focus", "reconciled"), log)
    }

    @Test fun staleTokenValidationAndInlineDriverFaultsAreObservable() {
        run {
            val driver = VirtualFrameDriver()
            val scheduler = scheduler(driver)
            var writes = 0
            scheduler.submitState("stale", isValid = { false }) { writes++ }
            scheduler.submitCommand(isValid = { error("token check failed") }) { writes++ }
            driver.tick()
            assertEquals(0, writes)
            assertEquals(1L, scheduler.counters.staleOperations)
            assertEquals("validation", scheduler.failure!!.phase)
            assertEquals(SchedulerState.FAULTED, scheduler.state)
        }
        run {
            val driver = object : UiFrameDriver {
                override val owner = "test.invalid-inline"
                override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
                    callback(FrameBudget(0))
                    return FrameTicket {}
                }
            }
            val scheduler = UiScheduler(driver, {})
            var writes = 0
            scheduler.submitCommand { writes++ }
            assertEquals(0, writes)
            assertEquals(SchedulerState.FAULTED, scheduler.state)
            assertEquals("driver", scheduler.failure!!.phase)
        }
    }

    @Test fun closeDuringActionOrValidationPreventsCommitAndRejectsReentry() {
        run {
            val driver = VirtualFrameDriver()
            val scheduler = scheduler(driver)
            val log = mutableListOf<String>()
            var afterClose: SubmitResult? = null
            scheduler.submitCommand {
                log += "entered"
                scheduler.close()
                afterClose = scheduler.submitCommand { log += "reentrant" }
                log += "existing-callback-finished"
            }
            scheduler.submitCommand { log += "never" }
            driver.tick()
            assertEquals(listOf("entered", "existing-callback-finished"), log)
            assertEquals(SubmitResult.Rejected(RejectionReason.CLOSED), afterClose)
            assertEquals(0, scheduler.pendingCommandCount)
            assertEquals(0, driver.pendingCount)
            scheduler.close()
        }
        run {
            val driver = VirtualFrameDriver()
            val scheduler = scheduler(driver)
            var writes = 0
            scheduler.submitCommand(isValid = { scheduler.close(); true }) { writes++ }
            driver.tick()
            assertEquals(0, writes)
        }
    }

    @Test fun driverCancelErrorStillClearsQueues() {
        run {
            val driver = object : UiFrameDriver {
                override val owner = "test.cancel-error-pause"
                override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket =
                    FrameTicket { throw AssertionError("cancel boom") }
            }
            val scheduler = UiScheduler(driver, {}, nanoTime = { 0 })
            scheduler.submitCommand { fail("must not run") }
            val failure = runCatching { scheduler.pause() }.exceptionOrNull()
            assertTrue("pause propagates the driver Error", failure is Error)
            assertEquals(0, scheduler.pendingCommandCount)
            assertEquals(0, scheduler.pendingStateCount)
        }
        run {
            // A frame ticket is pending here, so fault()'s cancelFrame reaches the failing ticket.
            val driver = object : UiFrameDriver {
                override val owner = "test.cancel-error-fault"
                override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket =
                    FrameTicket { throw AssertionError("cancel boom") }
            }
            val scheduler = UiScheduler(driver, {}, nanoTime = { 0 })
            scheduler.submitCommand(isValid = { throw IllegalStateException("validator boom") }, action = { })
            val failure = runCatching { scheduler.discardInvalid() }.exceptionOrNull()
            assertTrue("fault propagates the driver Error but was: $failure", failure is Error)
            assertEquals(SchedulerState.FAULTED, scheduler.state)
            assertEquals(0, scheduler.pendingCommandCount)
        }
    }

    @Test fun estimatedDeadlineIsIgnored() {
        val driver = VirtualFrameDriver()
        var now = 0L
        val scheduler = scheduler(driver, clock = { now })
        val log = mutableListOf<Int>()
        repeat(4) { index -> scheduler.submitCommand { log += index; now += 1_000_000L } }
        // A 0.5ms deadline would stop after one operation if it were honoured; estimated=true
        // must ignore it, so the fixed 2ms budget applies (2 operations).
        driver.tick(FrameBudget(0, deadlineNanos = 500_000L, estimated = true))
        assertEquals(listOf(0, 1), log)
        assertEquals(2, scheduler.pendingCommandCount)
    }

    @Test fun derivedBudgetUsesSoftDeadline() {
        val driver = VirtualFrameDriver()
        var now = 0L
        val scheduler = scheduler(driver, clock = { now })
        val log = mutableListOf<Int>()
        repeat(4) { index -> scheduler.submitCommand { log += index; now += 1_000_000L } }
        // deadline=2ms, tail=1ms -> soft deadline 1ms -> min(2ms, 1ms) = 1ms -> 1 operation.
        // The raw deadline alone would have allowed two.
        driver.tick(FrameBudget(0, deadlineNanos = 2_000_000L, estimated = false))
        assertEquals(listOf(0), log)
        assertEquals(3, scheduler.pendingCommandCount)
        // The remaining work is preserved and processed on the next frame.
        now = 0
        driver.tick(FrameBudget(0, deadlineNanos = 2_000_000L, estimated = false))
        assertEquals(listOf(0, 1), log)
        assertEquals(2, scheduler.pendingCommandCount)
    }

    @Test fun cancelInvalidatesBeforeRemovalAndReentryWaitsForAnotherTick() {
        run {
            var delivered: ((FrameBudget) -> Unit)? = null
            var cancelled = false
            val driver = object : UiFrameDriver {
                override val owner = "test.racing"
                override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
                    delivered = callback
                    return FrameTicket { cancelled = true }
                }
            }
            val scheduler = UiScheduler(driver, {}, nanoTime = { 0 })
            var writes = 0
            scheduler.submitCommand { writes++ }
            scheduler.close()
            delivered!!(FrameBudget(0))
            assertTrue(cancelled)
            assertEquals(0, writes)
            assertEquals(1L, scheduler.counters.releasedOperations)
        }
        run {
            val driver = VirtualFrameDriver()
            val log = mutableListOf<Int>()
            driver.requestFrame { log += 9 }.cancel()
            driver.requestFrame {
                log += 1
                driver.requestFrame { log += 2 }
            }
            driver.tick()
            assertEquals(listOf(1), log)
            assertEquals(1, driver.pendingCount)
            driver.tick()
            assertEquals(listOf(1, 2), log)
            assertEquals(listOf("request:1", "cancel:1", "request:2", "tick:2", "request:3", "tick:3"), driver.events)
        }
    }

    @Test fun pauseClearsOrRetainsPendingAndResumeDoesNotReplayClearedWork() {
        run {
            val driver = VirtualFrameDriver()
            val scheduler = scheduler(driver)
            var writes = 0
            scheduler.submitCommand { writes++ }
            scheduler.pause()
            assertEquals(SubmitResult.Rejected(RejectionReason.PAUSED), scheduler.submitCommand {})
            assertEquals(0, driver.pendingCount)
            scheduler.resume()
            driver.tick()
            assertEquals(0, writes)
        }
        run {
            val driver = VirtualFrameDriver()
            val scheduler = scheduler(driver)
            val log = mutableListOf<Int>()
            scheduler.submitCommand { log += 1; scheduler.pause(clearPending = false) }
            scheduler.submitCommand { log += 2 }
            driver.tick()
            assertEquals(listOf(1), log)
            scheduler.resume()
            driver.tick()
            assertEquals(listOf(1, 2), log)
        }
    }

    @Test fun countBudgetAlternatesChannelsWithoutReorderingCommands() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver, maxOperations = 1)
        val log = mutableListOf<String>()
        scheduler.submitState("a") { log += "s1" }
        scheduler.submitState("b") { log += "s2" }
        scheduler.submitCommand { log += "c1" }
        scheduler.submitCommand { log += "c2" }
        repeat(4) { driver.tick() }
        assertEquals(listOf("s1", "c1", "s2", "c2"), log)
    }

    @Test fun expiredDeadlineAndElapsedBudgetStopAtOperationBoundaries() {
        val driver = VirtualFrameDriver()
        var time = 10L
        val scheduler = scheduler(driver, clock = { time })
        val log = mutableListOf<Int>()
        scheduler.submitCommand { log += 1; time += 2_000_001 }
        scheduler.submitCommand { log += 2 }
        driver.tick(FrameBudget(0, deadlineNanos = 5, estimated = false))
        assertTrue(log.isEmpty())
        driver.tick()
        assertEquals(listOf(1), log)
        driver.tick()
        assertEquals(listOf(1, 2), log)
    }

    @Test fun pendingReentrantStateSupersedesUnprocessedOldValueNextFrame() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver, maxOperations = 1)
        val log = mutableListOf<String>()
        scheduler.submitState("trigger") { scheduler.submitState("target") { log += "new" } }
        scheduler.submitState("target") { log += "old" }
        driver.tick()
        driver.tick()
        assertEquals(listOf("new"), log)
        assertEquals(1L, scheduler.counters.coalescedStates)
    }

    @Test fun ownerCheckIsAppliedToSubmissionAndClose() {
        val driver = VirtualFrameDriver()
        var owner = true
        val scheduler = UiScheduler(driver, { check(owner) { "wrong owner" } })
        owner = false
        assertThrows(IllegalStateException::class.java) { scheduler.submitCommand {} }
        assertThrows(IllegalStateException::class.java) { scheduler.close() }
        assertEquals(0, driver.pendingCount)
    }

    @Test fun stateGroupsRoundRobinEvenWhenOneBindingHasManyProperties() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver, maxOperations = 1)
        val log = mutableListOf<String>()
        repeat(4) { index -> scheduler.submitState(StateSlot("a", index)) { log += "a$index" } }
        scheduler.submitState(StateSlot("b", 0)) { log += "b" }
        scheduler.submitState(StateSlot("c", 0)) { log += "c" }
        repeat(3) { driver.tick() }
        assertEquals(listOf("a0", "b", "c"), log)
    }

    @Test fun discardInvalidReleasesCapacityAndCallbackDiscardDoesNotExecute() {
        run {
            val driver = VirtualFrameDriver()
            val scheduler = scheduler(driver, stateCapacity = 1, commandCapacity = 2)
            var valid = true
            scheduler.submitState("a", { valid }) { fail("stale state applied") }
            scheduler.submitBatch(listOf(
                ScheduledOperation("a", { valid }) { fail("stale batch applied") },
                ScheduledOperation("b", { valid }) { fail("stale batch applied") },
            ))
            valid = false
            scheduler.discardInvalid()
            assertEquals(0, scheduler.pendingStateCount)
            assertEquals(0, scheduler.pendingCommandCount)
            assertEquals(0, driver.pendingCount)
            assertEquals(3L, scheduler.counters.staleOperations)
            assertEquals(3L, scheduler.counters.releasedOperations)
        }
        run {
            val driver = VirtualFrameDriver()
            val scheduler = scheduler(driver)
            var valid = true
            scheduler.submitCommand { valid = false; scheduler.discardInvalid() }
            scheduler.submitCommand({ valid }) { fail("discarded operation ran") }
            driver.tick()
            assertEquals(1L, scheduler.counters.appliedOperations)
            assertEquals(1L, scheduler.counters.staleOperations)
            assertEquals(0, scheduler.pendingCommandCount)
        }
    }

    @Test fun stateCapacityCountsReentrantOverlapAndOldSnapshotRelease() {
        run {
            val driver = VirtualFrameDriver()
            val scheduler = scheduler(driver, stateCapacity = 2, maxOperations = 1)
            val log = mutableListOf<String>()
            scheduler.submitState("trigger") {
                assertEquals(1, scheduler.pendingStateCount)
                assertEquals(SubmitResult.Accepted, scheduler.submitState("target") { log += "new" })
                assertEquals(SubmitResult.Coalesced, scheduler.submitState("target") { log += "latest" })
                assertEquals(1, scheduler.pendingStateCount)
                assertEquals(SubmitResult.Accepted, scheduler.submitState("extra") { log += "extra" })
                assertEquals(2, scheduler.pendingStateCount)
                assertEquals(SubmitResult.Rejected(RejectionReason.STATE_CAPACITY), scheduler.submitState("full") {})
            }
            scheduler.submitState("target") { log += "old" }
            driver.tick()
            assertEquals(2, scheduler.pendingStateCount)
            repeat(2) { driver.tick() }
            assertEquals(listOf("latest", "extra"), log)
            assertEquals(0, scheduler.pendingStateCount)
            assertEquals(2L, scheduler.counters.coalescedStates)
        }
        run {
            val driver = VirtualFrameDriver()
            val scheduler = scheduler(driver, stateCapacity = 2)
            val log = mutableListOf<String>()
            scheduler.submitState("trigger") {
                scheduler.submitState("target") { log += "new" }
                assertEquals(1, scheduler.pendingStateCount)
            }
            scheduler.submitState("target") {
                log += "old"
                assertEquals(1, scheduler.pendingStateCount)
                scheduler.submitState("extra") { log += "extra" }
                assertEquals(2, scheduler.pendingStateCount)
            }
            driver.tick()
            assertEquals(listOf("old"), log)
            driver.tick()
            assertEquals(listOf("old", "new", "extra"), log)
            assertEquals(0, scheduler.pendingStateCount)
        }
    }

    @Test fun discardSnapshotOverlapKeepsTheNewStateAndGroupRotation() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver, stateCapacity = 3)
        var valid = true
        val log = mutableListOf<String>()
        scheduler.submitState(StateSlot("a", 0)) {
            valid = false
            scheduler.submitState(StateSlot("a", 1)) { log += "new" }
            scheduler.discardInvalid()
            assertEquals(2, scheduler.pendingStateCount)
        }
        scheduler.submitState(StateSlot("a", 1), { valid }) { fail("stale state applied") }
        scheduler.submitState(StateSlot("b", 0)) { log += "b" }
        driver.tick()
        assertEquals(listOf("b"), log)
        assertEquals(1, scheduler.pendingStateCount)
        driver.tick()
        assertEquals(listOf("b", "new"), log)
        assertEquals(1L, scheduler.counters.staleOperations)
    }

    @Test fun batchCopiesInputAndKeepsOriginalIndexesAfterDiscard() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver, maxOperations = 1)
        val log = mutableListOf<String>()
        val input = mutableListOf(
            ScheduledOperation("stale", { false }) { fail("stale command applied") },
            ScheduledOperation("first") { log += "first" },
            ScheduledOperation("failure") { error("failed") },
        )
        scheduler.submitBatch(input)
        input.clear()
        scheduler.discardInvalid()
        assertEquals(2, scheduler.pendingCommandCount)
        driver.tick()
        assertEquals(1, scheduler.pendingCommandCount)
        driver.tick()
        assertEquals(listOf("first"), log)
        assertEquals(2, scheduler.failure!!.operationIndex)
        assertEquals(1, scheduler.failure!!.appliedInBatch)
        assertEquals(0, scheduler.pendingCommandCount)
        assertEquals(3L, scheduler.counters.releasedOperations)
    }

    @Test fun retainingPauseAndCloseClearAllPendingCounts() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver, maxOperations = 1)
        scheduler.submitState("trigger") {
            scheduler.submitState("target") {}
            scheduler.submitCommand {}
            scheduler.pause(clearPending = false)
        }
        scheduler.submitState("target") {}
        scheduler.submitCommand {}
        driver.tick()
        assertEquals(1, scheduler.pendingStateCount)
        assertEquals(2, scheduler.pendingCommandCount)
        scheduler.close()
        assertEquals(0, scheduler.pendingStateCount)
        assertEquals(0, scheduler.pendingCommandCount)
        assertEquals(0, driver.pendingCount)
        assertEquals(5L, scheduler.counters.releasedOperations)
    }

    @Test fun varargBatchMatchesListAdmissionAndFifo() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver, commandCapacity = 2, maxOperations = 1)
        val log = mutableListOf<Int>()
        assertEquals(SubmitResult.Rejected(RejectionReason.EMPTY_BATCH), scheduler.submitBatch())
        assertEquals(SubmitResult.Accepted, scheduler.submitBatch(
            ScheduledOperation { log += 1 }, ScheduledOperation { log += 2 },
        ))
        assertEquals(SubmitResult.Rejected(RejectionReason.COMMAND_CAPACITY), scheduler.submitBatch(ScheduledOperation {}))
        repeat(2) { driver.tick() }
        assertEquals(listOf(1, 2), log)
        assertEquals(0, scheduler.pendingCommandCount)
    }

    @Test fun stateLookupDoesNotScanUnrelatedBindings() {
        var comparisons = 0
        class Binding(val id: Int) {
            override fun hashCode() = id
            override fun equals(other: Any?): Boolean {
                comparisons++
                return other is Binding && id == other.id
            }
        }
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver, maxOperations = 256)
        var writes = 0
        repeat(256) { scheduler.submitState(StateSlot(Binding(it), 0)) { writes++ } }
        comparisons = 0
        driver.tick()
        assertEquals(256, writes)
        assertTrue("unrelated binding comparisons: $comparisons", comparisons < 256 * 8)
    }

    @Test fun indexedStateGroupsPreserveFullRoundRobinOrder() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver, maxOperations = 256)
        val log = mutableListOf<Pair<Int, Int>>()
        repeat(32) { group ->
            repeat(8) { property ->
                scheduler.submitState(StateSlot(group, property)) { log += group to property }
            }
        }
        driver.tick()
        val expected = (0 until 8).flatMap { property -> (0 until 32).map { group -> group to property } }
        assertEquals(expected, log)
        assertEquals(0, scheduler.pendingStateCount)
        assertEquals(256L, scheduler.counters.appliedOperations)
    }

    @Test fun commandOnlyFrameWithReentrantStateWaitsForNextFrame() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver, maxOperations = 4)
        val log = mutableListOf<String>()
        scheduler.submitCommand {
            log += "command"
            scheduler.submitState("reentrant") { log += "state" }
        }
        driver.tick()
        assertEquals(listOf("command"), log)
        assertEquals(1, scheduler.pendingStateCount)
        driver.tick()
        assertEquals(listOf("command", "state"), log)
        assertEquals(0, scheduler.pendingStateCount)
    }

    @Test fun faultClearsSpareQueuesAndTheySurviveMultipleFullDrains() {
        run {
            val driver = VirtualFrameDriver()
            val scheduler = scheduler(driver, maxOperations = 4)
            val log = mutableListOf<String>()
            scheduler.submitCommand {
                log += "first"
                error("boom")
            }
            scheduler.submitCommand { log += "never" }
            driver.tick()
            assertEquals(SchedulerState.FAULTED, scheduler.state)
            assertEquals(0, scheduler.pendingStateCount)
            assertEquals(0, scheduler.pendingCommandCount)
            scheduler.recover()
            scheduler.submitCommand { log += "recovered" }
            driver.tick()
            assertEquals(listOf("first", "recovered"), log)
        }
        run {
            val driver = VirtualFrameDriver()
            val scheduler = scheduler(driver, maxOperations = 2)
            val log = mutableListOf<Int>()
            repeat(4) { round ->
                scheduler.submitState("s") { log += round }
                scheduler.submitCommand { log += round * 10 }
                driver.tick()
            }
            assertEquals(listOf(0, 0, 1, 10, 2, 20, 3, 30), log)
            assertEquals(0, scheduler.pendingStateCount)
            assertEquals(0, scheduler.pendingCommandCount)
        }
    }

    @Test fun equalButDistinctBindingsShareOneGroup() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver, maxOperations = 1)
        val log = mutableListOf<String>()
        scheduler.submitState(StateSlot(EqualBinding(1), 0)) { log += "a" }
        scheduler.submitState(StateSlot(EqualBinding(1), 1)) { log += "b" }
        scheduler.submitState(StateSlot(EqualBinding(2), 0)) { log += "c" }
        repeat(3) { driver.tick() }
        assertEquals(listOf("a", "c", "b"), log)
        assertEquals(0, scheduler.pendingStateCount)
    }

    @Test fun cachedIndexDropsObjectReferencesAfterFrame() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver, maxOperations = 4)
        val binding = Any()
        scheduler.submitState(StateSlot(binding, 0)) { }
        driver.tick()
        val spare = field(scheduler, "spareIndex")
        assertNotNull("index buffers must be cached for reuse", spare)
        assertTrue("cached slots must be cleared", (field(spare, "slots") as Array<*>).all { it == null })
        val groups = field(spare, "groups")
        assertTrue("cached group keys must be cleared", (field(groups, "keys") as Array<*>).all { it == null })
        assertTrue("cached group key table must be cleared", (field(groups, "groupKeys") as Array<*>).all { it == null })
    }

    @Test fun groupTableBoundAllowsDistinctAndHashCollidingGroupsAtCapacityThirteen() {
        run {
            val driver = VirtualFrameDriver()
            val scheduler = scheduler(driver, stateCapacity = 13, maxOperations = 13)
            val log = mutableListOf<Int>()
            repeat(13) { index ->
                scheduler.submitState(StateSlot(DistinctBinding(index), 0)) { log += index }
            }
            driver.tick()
            assertEquals((0 until 13).toList(), log)
            assertEquals(0, scheduler.pendingStateCount)
        }
        run {
            val driver = VirtualFrameDriver()
            val scheduler = scheduler(driver, stateCapacity = 13, maxOperations = 13)
            val log = mutableListOf<Int>()
            repeat(13) { index ->
                scheduler.submitState(StateSlot(CollidingBinding(index), 0)) { log += index }
            }
            driver.tick()
            assertEquals((0 until 13).toList(), log)
            assertEquals(0, scheduler.pendingStateCount)
        }
    }

    private class DistinctBinding(val id: Int) {
        override fun hashCode() = id
        override fun equals(other: Any?): Boolean = other is DistinctBinding && id == other.id
    }

    private class CollidingBinding(val id: Int) {
        override fun hashCode() = 1
        override fun equals(other: Any?): Boolean = other is CollidingBinding && id == other.id
    }

    private fun field(target: Any?, name: String): Any? {
        val found = target!!.javaClass.getDeclaredField(name)
        found.isAccessible = true
        return found.get(target)
    }

    private class EqualBinding(val id: Int) {
        override fun hashCode() = id
        override fun equals(other: Any?): Boolean = other is EqualBinding && id == other.id
    }
}
