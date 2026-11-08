package io.github.jichuo1.meguviz.ui.scheduler

import io.github.jichuo1.meguviz.ui.testkit.VirtualFrameDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A producer whose `post` is refused is told [SubmitResult.Rejected], so its work must never run.
 *
 * `AsyncHandoffAdversarialTest` pins the case where the refusal is rolled back before the owner
 * looks at the handoff. These tests pin the other ordering: the owner has already merged the staged
 * entry into its own queue (the merge moves the entry object, it does not copy it) by the time the
 * producer learns that its `post` failed. The verdict and what actually runs must still agree: a
 * submission is either reported as accepted and runs, or reported as rejected and never runs.
 */
class RefusedDispatchAfterMergeTest {

    /** `post` runs the owner-side merge to completion, then reports the refusal. */
    private class MergeThenRefuse : OwnerThreadDispatcher {
        var onPost: (() -> Unit)? = null
        override fun post(callback: Runnable): Boolean {
            callback.run()
            onPost?.invoke()
            return false
        }
    }

    private fun scheduler(driver: VirtualFrameDriver, dispatcher: OwnerThreadDispatcher): UiScheduler =
        UiScheduler(driver, {}, 256, 256, 64, Long.MAX_VALUE, { 0L }, dispatcher)

    @Test
    fun stateMergedBeforeTheRefusalIsReportedHonestly() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver, MergeThenRefuse())
        var ran = false

        val result = scheduler.submitStateAsync("slot") { ran = true }
        repeat(4) { driver.tick() }

        assertEquals("a rejected submission must never run; result=$result", result !is SubmitResult.Rejected, ran)
    }

    @Test
    fun commandMergedBeforeTheRefusalIsReportedHonestly() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver, MergeThenRefuse())
        var ran = false

        val result = scheduler.submitCommandAsync { ran = true }
        repeat(4) { driver.tick() }

        assertEquals("a rejected submission must never run; result=$result", result !is SubmitResult.Rejected, ran)
    }

    @Test
    fun diagnosticsAgreeWithTheVerdict() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver, MergeThenRefuse())
        var ran = false

        val result = scheduler.submitStateAsync("slot") { ran = true }
        repeat(4) { driver.tick() }

        val diagnostics = scheduler.counters
        if (result is SubmitResult.Rejected) {
            assertFalse("rejected work ran", ran)
            assertEquals("a rejected submission must not count as accepted", 0L, diagnostics.acceptedSubmissions)
        } else {
            assertTrue("accepted work must run", ran)
            assertEquals(1L, diagnostics.acceptedSubmissions)
        }
    }

    /**
     * The owner drains the staged entry (its action runs) inside the producer's refused `post`.
     * Whatever the verdict is, it must match whether the action ran.
     */
    @Test
    fun stateAppliedBeforeTheRefusalIsReportedHonestly() {
        val driver = VirtualFrameDriver()
        val dispatcher = MergeThenRefuse()
        dispatcher.onPost = { repeat(2) { driver.tick() } }
        val scheduler = scheduler(driver, dispatcher)
        var ran = false

        val result = scheduler.submitStateAsync("slot") { ran = true }

        assertEquals("verdict=$result but ran=$ran", result !is SubmitResult.Rejected, ran)
    }
}
