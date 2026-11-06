package io.github.jichuo1.meguviz.ui.frames

import android.os.Looper
import io.github.jichuo1.meguviz.ui.scheduler.FrameBudget
import io.github.jichuo1.meguviz.ui.scheduler.FrameTicket
import io.github.jichuo1.meguviz.ui.scheduler.UiFrameDriver

/**
 * One platform frame source shared by several [SharedFrameDriver] clients.
 *
 * The source delivers at most one callback per requested frame on the owner thread.
 * [SharedFrameDriver] never calls a client synchronously from [requestFrame]. A source that
 * violates either rule is rejected with an [IllegalStateException] instead of recursing or
 * corrupting the fan-out.
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
 * explicitly constructs a shared one. Every method asserts the main thread, matching
 * [io.github.jichuo1.meguviz.ui.UiSession], which is main-thread-only.
 *
 * Semantics:
 * - Every [requestFrame] call is an independent one-shot request with its own ticket. There is no
 *   client identity, so two calls are two requests; use one request per client and keep its ticket.
 * - A ticket only cancels its own request; a stale ticket cannot cancel a later one.
 * - Fan-out uses a snapshot, so a client may request, cancel or close from inside a callback;
 *   such work is deferred to the next frame.
 * - A client throwing a non-`Error` does not stop the others. An `Error` is recorded, the other
 *   clients still run, and the first `Error` is rethrown after the fan-out and state recovery so
 *   the platform still sees it (matching the single-driver contract).
 * - A [requestFrame] that throws accepted nothing: its callback is never delivered and it holds
 *   no capacity, so a caller may retry without accumulating duplicates. Requests accepted
 *   earlier keep their tickets and stay pending.
 * - A source failure while re-arming after a fan-out is not thrown into the platform frame
 *   callback (a non-`Error` there would crash the owner thread). It is recorded in `lastFailure`,
 *   the requests stay pending, and the next [requestFrame] retries the source. A driver that is
 *   never asked again keeps those requests until [close], which is visible through [pendingCount].
 *   An `Error` from the source is still rethrown.
 * - Only text is retained for diagnostics; no Throwable or client reference is kept.
 */
class SharedFrameDriver(
    private val source: FrameSource,
    override val owner: String = "meguviz.ui.shared-frame",
    private val ownerCheck: () -> Unit = ::assertMainThread,
) : UiFrameDriver, AutoCloseable {
    private class Pending(
        val callback: (FrameBudget) -> Unit,
        var cancelled: Boolean = false,
    )

    /** Next-frame requests, keyed by request id. Swapped with [snapshot] each frame. */
    private var active = LinkedHashMap<Long, Pending>()
    /** Requests captured for the frame currently being delivered. */
    private var snapshot = LinkedHashMap<Long, Pending>()
    private var nextRequest = 0L
    private var sourceTicket: FrameTicket? = null
    private var requested = false
    /** True while [FrameSource.requestFrame] runs; a synchronous callback is a contract violation. */
    private var requesting = false
    /** True between a successful source request and its delivery; a second delivery is a violation. */
    private var awaitingDelivery = false
    private var dispatching = false
    private var closed = false

    /** Last failure as text; never holds the Throwable or the client. */
    var lastFailure: String? = null
        private set

    /**
     * Requests waiting for the next frame. Requests already captured for the frame being delivered
     * are in flight and are not counted, so this reads 0 during a fan-out.
     */
    val pendingCount: Int get() = active.size

    // A lambda, not `::deliver`: a bound reference to a private function compiles to a synthetic
    // class that calls a public static `access$deliver`, which would leak into the JVM API baseline.
    private val onFrame: (FrameBudget) -> Unit = { budget -> deliver(budget) }

    override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
        assertOwner()
        check(!closed) { "Shared frame driver closed" }
        check(active.size < MAX_CLIENTS) { "Shared frame driver client capacity exceeded" }
        check(nextRequest != Long.MAX_VALUE) { "Shared frame driver request identities exhausted" }
        val request = ++nextRequest
        active[request] = Pending(callback)
        try {
            requestSourceIfNeeded()
        } catch (failure: Throwable) {
            // The caller gets an exception and no ticket, so it cannot cancel this request: keep it
            // and its callback would run later anyway, and every retry would add an uncancellable
            // duplicate. Roll back only this request; earlier ones still hold their tickets.
            active.remove(request)
            throw failure
        }
        return FrameTicket {
            assertOwner()
            if (active.remove(request) != null && active.isEmpty()) cancelSource()
            // The request may already have been captured for the frame being delivered; mark it so
            // the fan-out skips it without touching the next frame's map.
            snapshot[request]?.let { it.cancelled = true }
        }
    }

    private fun requestSourceIfNeeded() {
        if (requested || dispatching || active.isEmpty() || closed) return
        requested = true
        awaitingDelivery = true
        requesting = true
        try {
            sourceTicket = source.requestFrame(onFrame)
        } catch (failure: Throwable) {
            // Keep the accepted requests: the next requestFrame retries the source, and the other
            // clients' requests must not be dropped silently.
            requested = false
            awaitingDelivery = false
            sourceTicket = null
            lastFailure = "${failure.javaClass.name}: ${failure.message}"
            throw failure
        } finally {
            requesting = false
        }
    }

    private fun deliver(budget: FrameBudget) {
        // A synchronous callback would recurse through requestSourceIfNeeded; a second delivery for
        // one request would swap a map that the outer fan-out is still iterating.
        check(awaitingDelivery && !requesting) {
            "FrameSource must deliver asynchronously and at most once per request"
        }
        awaitingDelivery = false
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
        } finally {
            // Clear on every path: a leftover entry here would be swapped back into `active` by the
            // next fan-out and delivered a second time.
            snapshot.clear()
            dispatching = false
            if (closed) {
                active.clear()
            } else {
                try {
                    requestSourceIfNeeded()
                } catch (postFailure: Throwable) {
                    // This runs inside the platform frame callback, where a non-Error would crash
                    // the owner thread. requestSourceIfNeeded already recorded lastFailure and kept
                    // the requests pending; the next requestFrame retries the source.
                    if (postFailure is Error) {
                        if (firstError == null) throw postFailure
                        firstError.addSuppressed(postFailure)
                    }
                }
            }
            firstError?.let { throw it }
        }
    }

    /** Returns and clears the last recorded failure text. */
    fun takeFailure(): String? {
        assertOwner()
        val failure = lastFailure
        lastFailure = null
        return failure
    }

    private fun cancelSource() {
        requested = false
        awaitingDelivery = false
        val ticket = sourceTicket
        sourceTicket = null
        try {
            ticket?.cancel()
        } catch (failure: Exception) {
            // Never overwrite a client failure with a cancellation failure: the root cause matters.
            if (lastFailure == null) lastFailure = "${failure.javaClass.name}: ${failure.message}"
        }
    }

    override fun close() {
        assertOwner()
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

    private fun assertOwner() {
        ownerCheck()
    }

    // Both modifiers are required: a bare `const val` in a private companion still compiles to a
    // public static field on the outer class, which would freeze it into the JVM API baseline.
    private companion object {
        /** Bounded so a leaked client cannot grow the fan-out without limit. */
        private const val MAX_CLIENTS = 256

        private fun assertMainThread() {
            check(Looper.myLooper() === Looper.getMainLooper()) {
                "Shared frame driver requires the main thread"
            }
        }
    }
}
