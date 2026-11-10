package io.github.jichuo1.meguviz.ui.android

import io.github.jichuo1.meguviz.android.runtime.core.FrameScheduler
import io.github.jichuo1.meguviz.android.runtime.core.FrameTick
import io.github.jichuo1.meguviz.android.runtime.core.OwnerDispatcher
import io.github.jichuo1.meguviz.ui.scheduler.FrameBudget
import io.github.jichuo1.meguviz.ui.scheduler.FrameTicket
import io.github.jichuo1.meguviz.ui.scheduler.UiFrameDriver

/** Owns one channel on a caller-owned real MeguvizAndroid scheduler. Never creates another driver. */
class AndroidFrameDriver(
    private val dispatcher: OwnerDispatcher,
    scheduler: FrameScheduler,
    identity: Any,
) : UiFrameDriver, AutoCloseable {
    override val owner: String = "meguviz-android.runtime"
    private class Request(var callback: ((FrameBudget) -> Unit)?, var generation: Long = 0)
    private val request = Request(null)
    private var requestGeneration = 0L
    private var pending: Request? = null
    private var channel: FrameScheduler.FrameChannel? = null
    private var closeAction: (() -> Unit)? = null
    var isClosed: Boolean = false
        private set
    // Kept as a primitive so recording it does not box once per frame; the getter boxes on demand.
    private var frameSequence = 0L
    private var frameDelivered = false
    val lastFrameSequence: Long? get() = if (frameDelivered) frameSequence else null
    // Invariant: source == "$owner:$upstreamSource". The upstream label rarely changes, so the
    // joined string is rebuilt only when it does instead of once per frame.
    private var upstreamSource: String? = "unknown"
    private var source = "$owner:unknown"

    init {
        dispatcher.checkOwner()
        channel = scheduler.openChannel(identity, { tick, _ -> deliver(tick) }, 1).also { registered ->
            registered.onClose { closedByUpstream() }
        }
    }

    /** A channel close invalidates pending callbacks before notifying its UI owner. */
    fun onClose(action: () -> Unit) {
        dispatcher.checkOwner()
        check(!isClosed && closeAction == null) { "Frame driver closed or close listener already owned" }
        closeAction = action
    }

    override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
        dispatcher.checkOwner()
        check(!isClosed) { "Android frame channel closed" }
        check(pending == null) { "A UI session may have only one pending frame request" }
        // One reusable request slot: a session never has two pending requests, and the
        // generation keeps a stale ticket from clearing its replacement.
        val generation = ++requestGeneration
        request.callback = callback
        request.generation = generation
        pending = request
        try { checkNotNull(channel).requestFrame() }
        catch (failure: Throwable) {
            if (pending === request) pending = null
            request.callback = null
            throw failure
        }
        return FrameTicket {
            dispatcher.checkOwner()
            if (request.generation == generation && pending === request) {
                request.callback = null
                pending = null
                channel?.cancelPending()
            }
        }
    }

    private fun deliver(tick: FrameTick): Int {
        dispatcher.checkOwner()
        val request = pending ?: return 0
        pending = null
        val callback = request.callback
        request.callback = null
        if (isClosed || callback == null) return 0
        frameSequence = tick.sequence()
        frameDelivered = true
        // One upstream work unit is one bounded UI drain, not an unbounded second mailbox.
        callback(tick.toUiBudget(sourceOf(tick)))
        return 1
    }

    private fun sourceOf(tick: FrameTick): String {
        val upstream = tick.budgetSource()
        if (upstream != upstreamSource) {
            source = "$owner:$upstream"
            upstreamSource = upstream
        }
        return source
    }

    private fun closedByUpstream() {
        isClosed = true
        pending?.callback = null
        pending = null
        channel = null
        val action = closeAction
        closeAction = null
        action?.invoke()
    }

    override fun close() {
        dispatcher.checkOwner()
        if (isClosed) return
        pending?.callback = null
        pending = null
        val registered = channel
        channel = null
        try { registered?.close() }
        finally { if (!isClosed) closedByUpstream() }
    }
}

/**
 * Copies the upstream tick into a [FrameBudget].
 *
 * The derived work budget subtracts [FrameBudget.deadlineNanos] from the scheduler's
 * `System.nanoTime()`, so the upstream `FrameTick.deadlineNanos()` must share that time base.
 * Drivers that cannot guarantee it must leave the deadline null (`estimated = true`).
 *
 * [source] is the complete label, `"<driver owner>:<FrameTick.budgetSource()>"` for this tick;
 * each driver keeps it and rebuilds it only when the upstream part changes.
 */
internal fun FrameTick.toUiBudget(source: String): FrameBudget {
    // The platform only knows the deadline when the upstream driver supplied one; otherwise the
    // budget stays advisory (estimated) and the scheduler keeps its fixed work budget.
    val deadline = deadlineNanos()
    return FrameBudget(
        frameTimeNanos = frameTimeNanos(),
        deadlineNanos = deadline,
        estimated = deadline == null,
        source = source,
    )
}
