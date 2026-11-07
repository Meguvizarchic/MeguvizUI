package io.github.jichuo1.meguviz.ui.scheduler

import io.github.jichuo1.meguviz.ui.testkit.VirtualFrameDriver
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Contract for the asynchronous submission entry points.
 *
 * Locks down the three properties the feature promises: the owner queues are only ever touched on
 * the owner thread, the handoff is bounded, and the rejection reasons match the synchronous entry
 * points. A [UiScheduler] built without a dispatcher must behave exactly like the old one.
 */
class CrossThreadSubmissionTest {
    /** Collects owner dispatches so a test can count them instead of having them run unseen. */
    private class RecordingDispatcher : OwnerThreadDispatcher {
        val posted = ArrayDeque<Runnable>()
        var refusals = 0

        override fun post(callback: Runnable): Boolean {
            if (refusals > 0) { refusals--; return false }
            posted.addLast(callback)
            return true
        }

        fun drainAll() { while (posted.pollFirst()?.run() != null) Unit }
    }

    private fun scheduler(
        driver: VirtualFrameDriver,
        dispatcher: OwnerThreadDispatcher? = RecordingDispatcher(),
        stateCapacity: Int = 256,
        commandCapacity: Int = 256,
        maxOperations: Int = 64,
    ): UiScheduler = UiScheduler(
        driver, {}, stateCapacity, commandCapacity, maxOperations, Long.MAX_VALUE, { 0L }, dispatcher,
    )

    @Test fun asyncStateSubmissionMergesOnTheOwnerThreadAndRequestsOneFrame() {
        val driver = VirtualFrameDriver()
        val dispatcher = RecordingDispatcher()
        val scheduler = scheduler(driver, dispatcher)
        val log = mutableListOf<String>()
        repeat(8) { index -> scheduler.submitStateAsync("slot$index") { log += "s$index" } }
        assertEquals(0, log.size)
        assertEquals("one owner dispatch must serve every pending update", 1, dispatcher.posted.size)
        dispatcher.drainAll()
        assertEquals(8, scheduler.pendingStateCount)
        assertEquals(1, driver.pendingCount)
        driver.tick()
        assertEquals((0 until 8).map { "s$it" }, log)
    }

    @Test fun asyncStateSubmissionCoalescesSameSlotBeforeTheMerge() {
        val driver = VirtualFrameDriver()
        val dispatcher = RecordingDispatcher()
        val scheduler = scheduler(driver, dispatcher)
        val log = mutableListOf<String>()
        repeat(20) { index -> scheduler.submitStateAsync("hot") { log += "v$index" } }
        assertEquals(1, dispatcher.posted.size)
        assertEquals("the handoff is not the owner queue", 0, scheduler.pendingStateCount)
        dispatcher.drainAll()
        assertEquals(19L, scheduler.counters.coalescedStates)
        assertEquals(1, scheduler.pendingStateCount)
        driver.tick()
        assertEquals(listOf("v19"), log)
        assertEquals(1L, scheduler.counters.appliedOperations)
    }

    @Test fun asyncHandoffIsBoundedForNewSlots() {
        val driver = VirtualFrameDriver()
        val dispatcher = RecordingDispatcher()
        val scheduler = scheduler(driver, dispatcher, stateCapacity = 2)
        assertEquals(SubmitResult.Accepted, scheduler.submitStateAsync("a") {})
        assertEquals(SubmitResult.Accepted, scheduler.submitStateAsync("b") {})
        // Replacing a staged slot still fits; a third distinct slot does not.
        assertEquals(SubmitResult.Coalesced, scheduler.submitStateAsync("a") {})
        assertEquals(SubmitResult.Rejected(RejectionReason.STATE_CAPACITY), scheduler.submitStateAsync("c") {})
        dispatcher.drainAll()
        assertEquals(2, scheduler.pendingStateCount)
    }

    @Test fun asyncCommandHandoffIsBounded() {
        val driver = VirtualFrameDriver()
        val dispatcher = RecordingDispatcher()
        val scheduler = scheduler(driver, dispatcher, commandCapacity = 1)
        assertEquals(SubmitResult.Accepted, scheduler.submitCommandAsync {})
        assertEquals(SubmitResult.Rejected(RejectionReason.COMMAND_CAPACITY), scheduler.submitCommandAsync {})
        dispatcher.drainAll()
        assertEquals(1, scheduler.pendingCommandCount)
    }

    @Test fun asyncSubmissionRejectsWithTheSameSchedulerStateReasons() {
        val driver = VirtualFrameDriver()
        val dispatcher = RecordingDispatcher()
        val scheduler = scheduler(driver, dispatcher)
        scheduler.pause()
        assertEquals(SubmitResult.Rejected(RejectionReason.PAUSED), scheduler.submitStateAsync("a") {})
        assertEquals(SubmitResult.Rejected(RejectionReason.PAUSED), scheduler.submitCommandAsync {})
        scheduler.resume()
        scheduler.submitCommand(isValid = { throw IllegalStateException("boom") }) {}
        driver.tick()
        assertEquals(SchedulerState.FAULTED, scheduler.state)
        assertEquals(SubmitResult.Rejected(RejectionReason.FAULTED), scheduler.submitStateAsync("a") {})
        scheduler.close()
        assertEquals(SubmitResult.Rejected(RejectionReason.CLOSED), scheduler.submitStateAsync("a") {})
        assertEquals(SubmitResult.Rejected(RejectionReason.CLOSED), scheduler.submitCommandAsync {})
    }

    @Test fun asyncSubmissionRunsActionsOnTheOwnerThread() {
        val driver = VirtualFrameDriver()
        val dispatcher = RecordingDispatcher()
        val scheduler = scheduler(driver, dispatcher)
        val owner = Thread.currentThread()
        val seen = mutableListOf<Thread?>()
        repeat(4) { scheduler.submitStateAsync("s$it") { seen += Thread.currentThread() } }
        dispatcher.drainAll()
        driver.tick()
        assertEquals(listOf(owner, owner, owner, owner), seen)
    }

    @Test fun asyncSubmissionFromAWorkerThreadNeverRunsTheActionThere() {
        val driver = VirtualFrameDriver()
        val dispatcher = RecordingDispatcher()
        val scheduler = scheduler(driver, dispatcher)
        val posted = CountDownLatch(1)
        val seen = AtomicReference<Thread?>()
        val worker = Thread {
            scheduler.submitStateAsync("from-worker") { seen.set(Thread.currentThread()) }
            posted.countDown()
        }
        worker.start()
        assertTrue("worker did not submit", posted.await(5, TimeUnit.SECONDS))
        worker.join(5_000)
        dispatcher.drainAll()
        driver.tick()
        assertNotSame(worker, seen.get())
        assertEquals(Thread.currentThread(), seen.get())
    }

    @Test fun asyncStateAndCommandShareASingleOwnerDispatch() {
        val driver = VirtualFrameDriver()
        val dispatcher = RecordingDispatcher()
        val scheduler = scheduler(driver, dispatcher)
        val log = mutableListOf<String>()
        scheduler.submitStateAsync("s1") { log += "s1" }
        scheduler.submitCommandAsync { log += "c1" }
        scheduler.submitStateAsync("s2") { log += "s2" }
        assertEquals(1, dispatcher.posted.size)
        dispatcher.drainAll()
        driver.tick()
        assertEquals(listOf("s1", "c1", "s2"), log)
    }

    @Test fun stagedWorkJoinsTheCurrentFrame() {
        val driver = VirtualFrameDriver()
        val dispatcher = RecordingDispatcher()
        val scheduler = scheduler(driver, dispatcher)
        val log = mutableListOf<String>()
        scheduler.submitState("sync") { log += "sync" }
        scheduler.submitStateAsync("async") { log += "async" }
        // The posted dispatch has not run, but the frame must still pick the staged work up.
        driver.tick()
        assertEquals(listOf("sync", "async"), log)
    }

    @Test fun acceptedWorkIsRetriedWhenTheOwnerQueueIsFullAndNeverDropped() {
        val driver = VirtualFrameDriver()
        val dispatcher = RecordingDispatcher()
        val scheduler = scheduler(driver, dispatcher, stateCapacity = 4, maxOperations = 2)
        val log = mutableListOf<String>()
        repeat(4) { scheduler.submitState("slot$it") { log += "s$it" } }
        assertEquals(SubmitResult.Accepted, scheduler.submitStateAsync("late") { log += "late" })
        // The owner queue is full, so this merge cannot take the staged update yet.
        dispatcher.drainAll()
        assertEquals(4, scheduler.pendingStateCount)
        repeat(3) { driver.tick() }
        assertEquals(listOf("s0", "s1", "s2", "s3", "late"), log)
    }

    @Test fun asyncSubmissionWithoutADispatcherIsRejectedAsClosed() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver, dispatcher = null)
        assertEquals(SubmitResult.Rejected(RejectionReason.CLOSED), scheduler.submitStateAsync("a") {})
        assertEquals(SubmitResult.Rejected(RejectionReason.CLOSED), scheduler.submitCommandAsync {})
        assertEquals(0, scheduler.pendingStateCount)
        assertEquals(0, scheduler.pendingCommandCount)
    }

    @Test fun refusedDispatchKeepsTheWorkStagedForALaterCall() {
        val driver = VirtualFrameDriver()
        val dispatcher = RecordingDispatcher().also { it.refusals = 1 }
        val scheduler = scheduler(driver, dispatcher)
        assertEquals(SubmitResult.Rejected(RejectionReason.CLOSED), scheduler.submitStateAsync("a") {})
        assertEquals(0, dispatcher.posted.size)
        // The item is still staged, so the next submission retries the dispatch.
        assertEquals(SubmitResult.Accepted, scheduler.submitStateAsync("b") {})
        assertEquals(1, dispatcher.posted.size)
        dispatcher.drainAll()
        assertEquals(2, scheduler.pendingStateCount)
    }
}
