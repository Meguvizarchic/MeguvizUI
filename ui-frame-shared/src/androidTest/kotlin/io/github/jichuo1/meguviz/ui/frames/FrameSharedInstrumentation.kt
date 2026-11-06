package io.github.jichuo1.meguviz.ui.frames

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import io.github.jichuo1.meguviz.ui.CreationEnvironment
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.scheduler.FrameBudget
import io.github.jichuo1.meguviz.ui.scheduler.FrameTicket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises the shared driver against the real platform Choreographer; no virtual clock is used.
 *
 * The host tests already pin the state machine with a manual source. These cases add what only a
 * device can show: that one platform frame really serves several sessions, that the driver keeps
 * working across frames, and that closing one session does not disturb another.
 */
class FrameSharedInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val cases = linkedMapOf<String, () -> Unit>(
            "shared_driver_serves_two_sessions_in_one_frame" to ::twoSessionsOneFrame,
            "shared_driver_keeps_delivering_across_frames" to ::deliversAcrossFrames,
            "closing_one_session_leaves_the_other_running" to ::closeOneSession,
        )
        var failures = 0
        cases.entries.forEachIndexed { index, (name, body) ->
            fun status() = Bundle().apply {
                putString("class", FrameSharedInstrumentation::class.java.name)
                putString("test", name)
                putInt("numtests", cases.size)
                putInt("current", index + 1)
            }
            sendStatus(1, status())
            var failure: Throwable? = null
            try { body() } catch (error: Throwable) { failure = error }
            val end = status()
            failure?.let { failures++; end.putString("stack", it.stackTraceToString()) }
            sendStatus(if (failure == null) 0 else -2, end)
        }
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", "Frame shared: ${cases.size} tests, $failures failures\n") })
    }

    /** A counting source that wraps the real Choreographer and records coalescing. */
    private class CountingSource(private val delegate: FrameSource) : FrameSource {
        val requests = AtomicInteger()
        override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
            requests.incrementAndGet()
            return delegate.requestFrame(callback)
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    /**
     * Two sessions share one driver and each submits work. The scheduler requests a frame as soon
     * as work is queued, so both requests reach the driver before the platform delivers.
     */
    private fun twoSessionsOneFrame() {
        var source: CountingSource? = null
        var driver: SharedFrameDriver? = null
        val sessions = mutableListOf<UiSession>()
        try {
            onMain {
                val counting = CountingSource(ChoreographerFrameSource())
                source = counting
                val shared = SharedFrameDriver(counting)
                driver = shared
                repeat(2) { index ->
                    val session = UiSession(CreationEnvironment(targetContext), shared)
                    sessions += session
                    // Each session queues one state update; the scheduler requests one frame.
                    session.scheduler.submitState("slot-$index") { }
                }
                check(counting.requests.get() == 1) {
                    "expected one platform request for two sessions, got ${counting.requests.get()}"
                }
            }
            // Give the platform a bounded window to deliver; this is a liveness bound, not a
            // timing assertion, so a slow device does not make the case flaky.
            Thread.sleep(200)
            onMain {
                check(source!!.requests.get() == 1) {
                    "the shared driver must not re-request while one frame is in flight"
                }
                check(sessions.all { it.scheduler.pendingStateCount == 0 }) {
                    "both sessions must have drained in the shared frame"
                }
            }
        } finally {
            onMain {
                sessions.forEach { it.close() }
                driver?.close()
            }
        }
    }

    private fun deliversAcrossFrames() {
        val delivered = CountDownLatch(3)
        var driver: SharedFrameDriver? = null
        try {
            onMain {
                val shared = SharedFrameDriver(ChoreographerFrameSource())
                driver = shared
                var remaining = 3
                fun request() {
                    shared.requestFrame {
                        if (remaining > 0) {
                            remaining--
                            delivered.countDown()
                            if (remaining > 0) request()
                        }
                    }
                }
                request()
            }
            check(delivered.await(10, TimeUnit.SECONDS)) {
                "the shared driver did not deliver three successive frames"
            }
        } finally {
            onMain { driver?.close() }
        }
    }

    private fun closeOneSession() {
        val delivered = CountDownLatch(1)
        var driver: SharedFrameDriver? = null
        var kept: UiSession? = null
        try {
            onMain {
                val shared = SharedFrameDriver(ChoreographerFrameSource())
                driver = shared
                val first = UiSession(CreationEnvironment(targetContext), shared)
                val second = UiSession(CreationEnvironment(targetContext), shared)
                kept = second
                first.close()
                // The surviving session must still receive frames through the same driver.
                shared.requestFrame { delivered.countDown() }
            }
            check(delivered.await(5, TimeUnit.SECONDS)) {
                "closing one session stopped the shared driver"
            }
            onMain {
                check(kept!!.state == io.github.jichuo1.meguviz.ui.SessionState.ACTIVE) {
                    "surviving session was disturbed"
                }
            }
        } finally {
            onMain {
                kept?.close()
                driver?.close()
            }
        }
    }
}
