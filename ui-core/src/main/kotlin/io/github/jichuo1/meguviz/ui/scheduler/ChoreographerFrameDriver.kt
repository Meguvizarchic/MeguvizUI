package io.github.jichuo1.meguviz.ui.scheduler

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.os.Build
import android.os.Looper
import android.view.Choreographer

/** Default platform driver. Android bridge sessions supply their own driver instead. */
class ChoreographerFrameDriver : UiFrameDriver {
    override val owner: String = "android.choreographer"
    private val choreographer: Choreographer
    private val vsyncDriver: UiFrameDriver?

    init {
        checkMainThread()
        choreographer = Choreographer.getInstance()
        // API 33+ exposes the frame deadline through postVsyncCallback; older releases keep the
        // fixed work budget and an estimated (advisory) budget. VsyncDriver is a separate class so
        // its Choreographer.VsyncCallback reference is only resolved on API 33+.
        vsyncDriver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) VsyncDriver(choreographer) else null
    }

    override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
        checkMainThread()
        val driver = vsyncDriver
        return if (driver != null) driver.requestFrame(callback) else legacyFrame(callback)
    }

    private fun legacyFrame(callback: (FrameBudget) -> Unit): FrameTicket {
        var pending: ((FrameBudget) -> Unit)? = callback
        val platformCallback = Choreographer.FrameCallback { time ->
            val consumer = pending
            pending = null
            consumer?.invoke(FrameBudget(time, source = owner))
        }
        choreographer.postFrameCallback(platformCallback)
        return FrameTicket {
            checkMainThread()
            pending = null
            choreographer.removeFrameCallback(platformCallback)
        }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() === Looper.getMainLooper()) { "Frame driver requires the main thread" }
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
    private class VsyncDriver(private val choreographer: Choreographer) : UiFrameDriver {
        override val owner: String = "android.choreographer"

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
                    // Platform timeline unavailable (e.g. SurfaceFlinger restart): stay alive with
                    // an advisory budget instead of dropping the frame and stalling the scheduler.
                }
                consumer(FrameBudget(data.frameTimeNanos, deadline, deadline == null, owner))
            }
            choreographer.postVsyncCallback(platformCallback)
            return FrameTicket {
                checkMainThread()
                pending = null
                choreographer.removeVsyncCallback(platformCallback)
            }
        }

        private fun checkMainThread() {
            check(Looper.myLooper() === Looper.getMainLooper()) { "Frame driver requires the main thread" }
        }
    }
}
