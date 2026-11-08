package io.github.jichuo1.meguviz.ui.scheduler

import io.github.jichuo1.meguviz.ui.testkit.VirtualFrameDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Adversarial contracts for the asynchronous handoff.
 *
 * Each test pins a failure mode that the happy-path contract tests cannot observe: a command-only
 * leftover with no pending frame, a refused dispatch interleaved with another producer's accepted
 * submission, and producer-side rejections counted from many threads at once.
 */
class AsyncHandoffAdversarialTest {

    private class Dispatcher : OwnerThreadDispatcher {
        val posted = ArrayDeque<Runnable>()
        var refusals = 0
        override fun post(callback: Runnable): Boolean {
            if (refusals > 0) {
                refusals--
                return false
            }
            posted.addLast(callback)
            return true
        }
        fun drainAll() {
            while (true) {
                val callback = posted.pollFirst() ?: break
                callback.run()
            }
        }
    }

    private fun scheduler(
        driver: VirtualFrameDriver,
        dispatcher: OwnerThreadDispatcher?,
        stateCapacity: Int = 256,
        commandCapacity: Int = 256,
        maxOperations: Int = 64,
    ): UiScheduler = UiScheduler(
        driver, {}, stateCapacity, commandCapacity, maxOperations, Long.MAX_VALUE, { 0L }, dispatcher,
    )

    /** A staged command that does not fit must still be delivered when no other owner work exists. */
    @Test
    fun commandOnlyLeftoverIsNeverStranded() {
        val driver = VirtualFrameDriver()
        val dispatcher = Dispatcher()
        val scheduler = scheduler(driver, dispatcher, commandCapacity = 1)
        val log = mutableListOf<String>()
        assertEquals(SubmitResult.Accepted, scheduler.submitCommand { log += "sync" })
        assertEquals(SubmitResult.Accepted, scheduler.submitCommandAsync { log += "async" })
        dispatcher.drainAll()
        repeat(6) { driver.tick() }
        assertEquals(listOf("sync", "async"), log)
        assertEquals(0, scheduler.pendingCommandCount)
    }

    /**
     * P1 wins the dispatch CAS but its post is refused; P2 stages while P1 is inside post and is
     * told "Accepted" without posting its own dispatch. P1's rollback must not void P2's work, and
     * a replacement dispatch must exist for it.
     */
    @Test
    fun refusedDispatchDoesNotVoidAConcurrentProducersAcceptedWork() {
        val driver = VirtualFrameDriver()
        val enteredPost = CountDownLatch(1)
        val releasePost = CountDownLatch(1)
        val posted = ArrayDeque<Runnable>()
        var firstPost = true
        val dispatcher = object : OwnerThreadDispatcher {
            override fun post(callback: Runnable): Boolean {
                if (firstPost) {
                    firstPost = false
                    enteredPost.countDown()
                    assertTrue("P1 was never released", releasePost.await(5, TimeUnit.SECONDS))
                    return false
                }
                posted.addLast(callback)
                return true
            }
        }
        val scheduler = scheduler(driver, dispatcher)
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        val firstResult = AtomicReference<SubmitResult>()
        val secondResult = AtomicReference<SubmitResult>()

        val p1 = Thread { firstResult.set(scheduler.submitStateAsync("a") { log += "a" }) }
        p1.start()
        assertTrue("P1 did not reach post", enteredPost.await(5, TimeUnit.SECONDS))
        val p2 = Thread { secondResult.set(scheduler.submitStateAsync("b") { log += "b" }) }
        p2.start()
        p2.join(5_000)
        releasePost.countDown()
        p1.join(5_000)

        assertEquals(SubmitResult.Rejected(RejectionReason.CLOSED), firstResult.get())
        assertEquals("P2 must be told the truth about its submission", SubmitResult.Accepted, secondResult.get())
        while (true) {
            val callback = posted.pollFirst() ?: break
            callback.run()
        }
        repeat(4) { driver.tick() }
        assertTrue("an Accepted submission must eventually run; log=$log", log.contains("b"))
        assertTrue("a Rejected submission must never run; log=$log", !log.contains("a"))
    }

    /** Producer-side rejections are written from many threads; none may be lost. */
    @Test
    fun concurrentRejectionsAreAllCounted() {
        val driver = VirtualFrameDriver()
        val dispatcher = Dispatcher()
        val scheduler = scheduler(driver, dispatcher)
        scheduler.pause()
        val threads = 8
        val perThread = 500
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            Thread {
                start.await()
                repeat(perThread) {
                    assertEquals(SubmitResult.Rejected(RejectionReason.PAUSED), scheduler.submitCommandAsync {})
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue("producers did not finish", done.await(20, TimeUnit.SECONDS))
        scheduler.close()
        assertEquals(
            "every rejected submission must be observable",
            (threads * perThread).toLong(),
            scheduler.counters.rejectedSubmissions,
        )
    }

    /** A refusal must not leave a hidden terminal flag: once the dispatcher recovers, async works. */
    @Test
    fun repeatedRefusalsDoNotPermanentlyDisableAsyncSubmissions() {
        val driver = VirtualFrameDriver()
        val dispatcher = Dispatcher().also { it.refusals = 2 }
        val scheduler = scheduler(driver, dispatcher)
        val log = mutableListOf<String>()
        assertEquals(SubmitResult.Rejected(RejectionReason.CLOSED), scheduler.submitStateAsync("s") { log += "v" })
        assertEquals(SubmitResult.Rejected(RejectionReason.CLOSED), scheduler.submitStateAsync("s") { log += "v" })
        assertEquals("a recovered dispatcher must accept again", SubmitResult.Accepted, scheduler.submitStateAsync("s") { log += "v" })
        dispatcher.drainAll()
        driver.tick()
        assertEquals(listOf("v"), log)
    }

    /** The unwind must keep other producers' diagnostics instead of zeroing the whole generation. */
    @Test
    fun refusedDispatchPreservesOtherProducersDiagnostics() {
        val driver = VirtualFrameDriver()
        val enteredPost = CountDownLatch(1)
        val releasePost = CountDownLatch(1)
        val posted = ArrayDeque<Runnable>()
        var firstPost = true
        val dispatcher = object : OwnerThreadDispatcher {
            override fun post(callback: Runnable): Boolean {
                if (firstPost) {
                    firstPost = false
                    enteredPost.countDown()
                    assertTrue("P1 was never released", releasePost.await(5, TimeUnit.SECONDS))
                    return false
                }
                posted.addLast(callback)
                return true
            }
        }
        val scheduler = scheduler(driver, dispatcher, stateCapacity = 2)
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        val first = AtomicReference<SubmitResult>()
        val p1 = Thread { first.set(scheduler.submitStateAsync("a") { log += "a" }) }
        p1.start()
        assertTrue("P1 did not reach post", enteredPost.await(5, TimeUnit.SECONDS))
        assertEquals(SubmitResult.Accepted, scheduler.submitStateAsync("b") { log += "b" })
        // A third producer is rejected on capacity while P1 is still inside post.
        assertEquals(
            SubmitResult.Rejected(RejectionReason.STATE_CAPACITY),
            scheduler.submitStateAsync("c") { log += "c" },
        )
        releasePost.countDown()
        p1.join(5_000)
        assertEquals(SubmitResult.Rejected(RejectionReason.CLOSED), first.get())
        while (true) {
            val callback = posted.pollFirst() ?: break
            callback.run()
        }
        driver.tick()
        assertTrue("P2's Accepted work must run; log=$log", log.contains("b"))
        assertEquals(1L, scheduler.counters.acceptedSubmissions)
        assertEquals("both P1's and P3's rejections must survive", 2L, scheduler.counters.rejectedSubmissions)
    }

    /** Rolling back a coalesced submission must not restore a value a later producer has replaced. */
    @Test
    fun sameSlotRollbackKeepsTheLaterAcceptedValue() {
        val driver = VirtualFrameDriver()
        val enteredPost = CountDownLatch(1)
        val releasePost = CountDownLatch(1)
        val posted = ArrayDeque<Runnable>()
        var firstPost = true
        val dispatcher = object : OwnerThreadDispatcher {
            override fun post(callback: Runnable): Boolean {
                if (firstPost) {
                    firstPost = false
                    enteredPost.countDown()
                    assertTrue("P1 was never released", releasePost.await(5, TimeUnit.SECONDS))
                    return false
                }
                posted.addLast(callback)
                return true
            }
        }
        val scheduler = scheduler(driver, dispatcher)
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        val first = AtomicReference<SubmitResult>()
        val p1 = Thread { first.set(scheduler.submitStateAsync("hot") { log += "v1" }) }
        p1.start()
        assertTrue("P1 did not reach post", enteredPost.await(5, TimeUnit.SECONDS))
        assertEquals(SubmitResult.Coalesced, scheduler.submitStateAsync("hot") { log += "v2" })
        releasePost.countDown()
        p1.join(5_000)
        assertEquals(SubmitResult.Rejected(RejectionReason.CLOSED), first.get())
        while (true) {
            val callback = posted.pollFirst() ?: break
            callback.run()
        }
        driver.tick()
        assertEquals("the later accepted value must win; log=$log", listOf("v2"), log)
    }
}
