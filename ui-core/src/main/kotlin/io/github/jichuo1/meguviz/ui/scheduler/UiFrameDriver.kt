package io.github.jichuo1.meguviz.ui.scheduler

/**
 * Copied monotonic timing values. No platform callback object escapes its callback.
 *
 * @param frameTimeNanos the frame's vsync timestamp (`System.nanoTime` base).
 * @param deadlineNanos the platform's frame deadline when it is known; null when it is not.
 * @param estimated true when [deadlineNanos] is unknown or only an estimate (the scheduler then
 *   keeps its fixed work budget); false when the platform supplied a real deadline.
 * @param source identifies the driver/platform path that produced this budget.
 */
data class FrameBudget(
    val frameTimeNanos: Long,
    val deadlineNanos: Long? = null,
    val estimated: Boolean = true,
    val source: String = "unknown",
)

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
