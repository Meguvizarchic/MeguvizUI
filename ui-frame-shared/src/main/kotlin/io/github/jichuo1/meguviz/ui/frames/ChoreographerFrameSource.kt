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
    private val vsyncSupported: Boolean

    init {
        checkMainThread()
        choreographer = Choreographer.getInstance()
        vsyncSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
        checkMainThread()
        return if (vsyncSupported) vsyncFrame(callback) else legacyFrame(callback)
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

    // The SDK_INT guard lives in the caller, so the API requirement does not propagate.
    @SuppressLint("UseRequiresApi")
    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun vsyncFrame(callback: (FrameBudget) -> Unit): FrameTicket {
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

    private companion object {
        const val SOURCE = "meguviz.ui.shared-frame.choreographer"
    }
}
