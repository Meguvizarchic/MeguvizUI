package io.github.jichuo1.meguviz.ui.frames

import io.github.jichuo1.meguviz.ui.scheduler.FrameBudget
import io.github.jichuo1.meguviz.ui.scheduler.FrameTicket
import io.github.jichuo1.meguviz.ui.scheduler.UiFrameDriver

/**
 * One platform frame source shared by several [SharedFrameDriver] clients.
 *
 * The source delivers at most one callback per requested frame on the owner thread.
 * [SharedFrameDriver] never calls a client synchronously from [requestFrame].
 */
interface FrameSource {
    /** Enqueues one frame opportunity; [callback] must not be invoked before this returns. */
    fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket
}

/**
 * Optional per-thread driver that multiplexes many [UiFrameDriver] clients onto one
 * [FrameSource] request per frame.
 *
 * This module is never required by the core: a session keeps its own driver unless the caller
 * explicitly constructs a shared one. All methods must run on the owner thread.
 *
 * Semantics:
 * - At most one pending request per client; a second request while one is pending is rejected.
 * - A ticket only cancels its own request; a stale ticket cannot cancel a later one.
 * - Fan-out uses a snapshot, so a client may request, cancel or close from inside a callback;
 *   such work is deferred to the next frame.
 * - A client throwing a non-`Error` does not stop the others. An `Error` is recorded, the other
 *   clients still run, and the first `Error` is rethrown after the fan-out and state recovery so
 *   the platform still sees it (matching the single-driver contract).
 * - Only text is retained for diagnostics; no Throwable or client reference is kept.
 */
class SharedFrameDriver(
    private val source: FrameSource,
    override val owner: String = "meguviz.ui.shared-frame",
) : UiFrameDriver, AutoCloseable {
    private class Pending(
        val callback: (FrameBudget) -> Unit,
        val generation: Long,
        var cancelled: Boolean = false,
    )

    /** Next-frame requests, keyed by client id. Swapped with [snapshot] each frame. */
    private var active = LinkedHashMap<Long, Pending>()
    /** Requests captured for the frame currently being delivered. */
    private var snapshot = LinkedHashMap<Long, Pending>()
    private var nextClient = 0L
    private var generation = 0L
    private var sourceTicket: FrameTicket? = null
    private var requested = false
    private var dispatching = false
    private var closed = false
    /** Set when the source itself failed; further requests fail fast instead of silently queuing. */
    private var faulted = false

    /** Last callback failure as text; never holds the Throwable or the client. */
    var lastFailure: String? = null
        private set

    val pendingCount: Int get() = active.size

    override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
        check(!closed) { "Shared frame driver closed" }
        check(!faulted) { "Shared frame driver source failed; recreate the driver" }
        val client = ++nextClient
        val ticketGeneration = ++generation
        check(active.size < MAX_CLIENTS) { "Shared frame driver client capacity exceeded" }
        active[client] = Pending(callback, ticketGeneration)
        requestSourceIfNeeded()
        return FrameTicket {
            val entry = active[client]
            if (entry != null && entry.generation == ticketGeneration) {
                active.remove(client)
                if (active.isEmpty()) cancelSource()
            } else {
                // The request may already have been captured for the frame being delivered;
                // mark it so the fan-out skips it without touching the next frame's map.
                snapshot[client]?.let { if (it.generation == ticketGeneration) it.cancelled = true }
            }
        }
    }

    private fun requestSourceIfNeeded() {
        if (requested || dispatching || active.isEmpty() || closed) return
        requested = true
        try {
            sourceTicket = source.requestFrame(::deliver)
        } catch (failure: Throwable) {
            requested = false
            sourceTicket = null
            // Keep the pending requests: the caller can still recover by requesting again after
            // the source is healthy, and other clients' requests must not be dropped silently.
            faulted = true
            lastFailure = "${failure.javaClass.name}: ${failure.message}"
            throw failure
        }
    }

    private fun deliver(budget: FrameBudget) {
        requested = false
        sourceTicket = null
        if (closed) return
        dispatching = true
        var firstError: Error? = null
        try {
            // Double-buffer swap: `snapshot` becomes this frame's work and `active` collects
            // requests made from inside the callbacks for the next frame.
            val batch = active
            active = snapshot
            snapshot = batch
            for (entry in batch.values) {
                // A callback may close the driver; stop handing out frames but let the iterator
                // finish over the captured map (close() never clears it while dispatching).
                if (closed) break
                if (entry.cancelled) continue
                try {
                    entry.callback(budget)
                } catch (failure: Throwable) {
                    lastFailure = "${failure.javaClass.name}: ${failure.message}"
                    if (failure is Error && firstError == null) firstError = failure
                }
            }
            batch.clear()
        } finally {
            dispatching = false
            if (closed) {
                active.clear()
                snapshot.clear()
            } else {
                try {
                    requestSourceIfNeeded()
                } catch (postFailure: Throwable) {
                    if (firstError == null) throw postFailure
                    firstError.addSuppressed(postFailure)
                }
            }
            firstError?.let { throw it }
        }
    }

    /** Returns and clears the last recorded callback failure text. */
    fun takeFailure(): String? {
        val failure = lastFailure
        lastFailure = null
        return failure
    }

    private fun cancelSource() {
        requested = false
        val ticket = sourceTicket
        sourceTicket = null
        try { ticket?.cancel() } catch (failure: Exception) { lastFailure = "${failure.javaClass.name}: ${failure.message}" }
    }

    override fun close() {
        if (closed) return
        closed = true
        // While a fan-out is in progress `snapshot` is the map being iterated; clearing it would
        // throw ConcurrentModificationException into the platform callback. The fan-out stops at
        // the next entry because it checks `closed`, and the maps are cleared in its finally.
        if (!dispatching) {
            active.clear()
            snapshot.clear()
        }
        cancelSource()
    }

    private companion object {
        /** Bounded so a leaked client cannot grow the fan-out without limit. */
        const val MAX_CLIENTS = 256
    }
}
