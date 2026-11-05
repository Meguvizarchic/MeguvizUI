package io.github.jichuo1.meguviz.ui.scheduler

/**
 * Copied monotonic timing values. No platform callback object escapes its callback.
 *
 * @param frameTimeNanos the frame's vsync timestamp (`System.nanoTime` base).
 * @param deadlineNanos the platform's frame deadline when it is known; null when it is not.
 * @param estimated true when [deadlineNanos] is unknown or only an estimate (the scheduler then
 *   keeps its fixed work budget); false when the platform supplied a real deadline. A non-null
 *   [deadlineNanos] combined with `estimated = true` is rejected.
 * @param source identifies the driver/platform path that produced this budget.
 */
data class FrameBudget(
    val frameTimeNanos: Long,
    val deadlineNanos: Long? = null,
    val estimated: Boolean = true,
    val source: String = "unknown",
) {
    init {
        // A deadline that is merely estimated must not be paired with estimated=true: the scheduler
        // would silently ignore it. Fail closed instead.
        require(estimated || deadlineNanos != null) {
            "estimated=false requires a platform deadline; use estimated=true for an advisory budget"
        }
    }
}

fun interface FrameTicket {
    /** Releases the driver's callback reference. A racing callback must still be invalidated. */
    fun cancel()
}

/** One immutable driver is selected when a session is assembled. Methods run on its owner. */
interface UiFrameDriver {
    val owner: String

    /** Must enqueue one opportunity, never invoke [callback] synchronously. */
    fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket
}
