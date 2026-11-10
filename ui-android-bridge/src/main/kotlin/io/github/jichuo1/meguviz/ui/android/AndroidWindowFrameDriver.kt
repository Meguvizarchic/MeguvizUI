package io.github.jichuo1.meguviz.ui.android

import io.github.jichuo1.meguviz.android.runtime.core.FrameTick
import io.github.jichuo1.meguviz.android.runtime.core.Registration
import io.github.jichuo1.meguviz.android.window.WindowSession
import io.github.jichuo1.meguviz.ui.scheduler.FrameBudget
import io.github.jichuo1.meguviz.ui.scheduler.FrameTicket
import io.github.jichuo1.meguviz.ui.scheduler.UiFrameDriver

/** Window lifecycle gating is performed by the real upstream WindowFrameChannel. */
internal class AndroidWindowFrameDriver(window: WindowSession, identity: Any, initiallyAttached: Boolean) : UiFrameDriver, AutoCloseable {
    override val owner = "meguviz-android.window"
    private val dispatcher = window.dispatcher()
    private class Request(var callback: ((FrameBudget) -> Unit)?, var generation: Long = 0)
    private val request = Request(null)
    private var requestGeneration = 0L
    private var pending: Request? = null
    private var channel: WindowSession.WindowFrameChannel? = null
    private var registration: Registration? = null
    private var closed = false
    private var attached = initiallyAttached
    private var closeAction: (() -> Unit)? = null
    // Invariant: source == "$owner:$upstreamSource"; rebuilt only when the upstream label changes.
    private var upstreamSource: String? = "unknown"
    private var source = "$owner:unknown"

    init {
        dispatcher.checkOwner()
        channel = window.openFrameChannel(identity, { tick, _ -> deliver(tick) }, 1)
        try { registration = window.own(Registration(dispatcher) { close() }) }
        catch (failure: Throwable) {
            try { channel?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            channel = null
            throw failure
        }
    }

    fun onClose(action: () -> Unit) {
        dispatcher.checkOwner()
        check(!closed && closeAction == null)
        closeAction = action
    }

    fun setAttached(value: Boolean) {
        dispatcher.checkOwner()
        if (closed || attached == value) return
        attached = value
        if (!value) channel?.cancelPending()
        // close() sets closed before clearing channel, and setAttached returns early once closed,
        // so a non-null pending implies a live channel; fail fast if that invariant is ever broken.
        else if (pending != null) checkNotNull(channel).requestFrame()
    }

    override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
        dispatcher.checkOwner()
        check(!closed && pending == null) { "Window driver closed or frame already pending" }
        // One reusable request slot: a session never has two pending requests, and the
        // generation keeps a stale ticket from clearing its replacement.
        val generation = ++requestGeneration
        request.callback = callback
        request.generation = generation
        pending = request
        try { if (attached) checkNotNull(channel).requestFrame() }
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
        val request = pending ?: return 0
        pending = null
        val callback = request.callback
        request.callback = null
        if (closed || !attached || callback == null) return 0
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

    override fun close() {
        dispatcher.checkOwner()
        if (closed) return
        closed = true
        pending?.callback = null
        pending = null
        val ownedChannel = channel; channel = null
        val ownedRegistration = registration; registration = null
        val action = closeAction; closeAction = null
        closeAll(listOf({ ownedChannel?.close() }, { ownedRegistration?.close() }, { action?.invoke() }))
    }
}

internal fun closeAll(actions: List<() -> Unit>) {
    var failure: Throwable? = null
    actions.forEach { action ->
        try { action() }
        catch (error: Throwable) { if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error) }
    }
    failure?.let { throw it }
}
