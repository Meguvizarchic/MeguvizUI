package io.github.jichuo1.meguviz.ui.frames

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.os.Build
import android.os.Looper
import android.view.Choreographer
import io.github.jichuo1.meguviz.ui.scheduler.FrameBudget
import io.github.jichuo1.meguviz.ui.scheduler.FrameTicket

/**
 * Choreographer-backed [FrameSource]. One instance per owner thread; [SharedFrameDriver] keeps a
 * single platform callback alive for all of its clients.
 *
 * API 33+ copies the preferred timeline deadline (`estimated = false`); older releases report an
 * advisory budget (`deadlineNanos = null`, `estimated = true`).
 */
class ChoreographerFrameSource : FrameSource {
    private val choreographer: Choreographer
    private val vsyncSource: FrameSource?

    init {
        checkMainThread()
        choreographer = Choreographer.getInstance()
        // API 33+ exposes the frame deadline through postVsyncCallback; older releases keep the
        // fixed work budget and an advisory budget. VsyncSource is a separate class so its
        // Choreographer.VsyncCallback reference is only resolved on API 33+; the same reference in
        // this class body would abort class verification on API 27-32 and block optimization.
        vsyncSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) VsyncSource(choreographer) else null
    }

    override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
        checkMainThread()
        val source = vsyncSource
        return if (source != null) source.requestFrame(callback) else legacyFrame(callback)
    }

    private fun legacyFrame(callback: (FrameBudget) -> Unit): FrameTicket {
        var pending: ((FrameBudget) -> Unit)? = callback
        val platformCallback = Choreographer.FrameCallback { time ->
            val consumer = pending
            pending = null
            consumer?.invoke(FrameBudget(time, source = SOURCE))
        }
        choreographer.postFrameCallback(platformCallback)
        return FrameTicket {
            checkMainThread()
            pending = null
            choreographer.removeFrameCallback(platformCallback)
        }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() === Looper.getMainLooper()) { "Frame source requires the main thread" }
    }

    /**
     * API 33+ path: copies the preferred timeline's deadline so the scheduler can stop at the
     * real frame deadline instead of only its fixed work budget. The platform callback and the
     * timeline are only valid inside [onVsync], so the deadline is copied immediately.
     */
    // The SDK_INT guard lives in the caller, so the API requirement does not need to propagate
    // beyond this class; @RequiresApi would force every caller to restate it.
    @SuppressLint("UseRequiresApi")
    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private class VsyncSource(private val choreographer: Choreographer) : FrameSource {
        override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
            var pending: ((FrameBudget) -> Unit)? = callback
            val platformCallback = Choreographer.VsyncCallback { data ->
                val consumer = pending
                pending = null
                if (consumer == null) return@VsyncCallback
                var deadline: Long? = null
                try {
                    deadline = data.preferredFrameTimeline.deadlineNanos.takeIf { it > 0 }
                } catch (failure: Exception) {
                    // Platform timeline unavailable: keep an advisory budget instead of stalling.
                }
                consumer(FrameBudget(data.frameTimeNanos, deadline, deadline == null, SOURCE))
            }
            choreographer.postVsyncCallback(platformCallback)
            return FrameTicket {
                checkMainThread()
                pending = null
                choreographer.removeVsyncCallback(platformCallback)
            }
        }

        private fun checkMainThread() {
            check(Looper.myLooper() === Looper.getMainLooper()) { "Frame source requires the main thread" }
        }
    }

    // Both modifiers are required: a bare `const val` in a private companion still compiles to a
    // public static field on the outer class, which would freeze it into the JVM API baseline.
    private companion object {
        private const val SOURCE = "meguviz.ui.shared-frame.choreographer"
    }
}
