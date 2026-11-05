package io.github.jichuo1.meguviz.ui.text

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Owner-thread admission. A running calculation is never interrupted; only the latest result commits. */
internal class LatestTextWork<I, O>(
    private val background: Executor,
    private val owner: Executor,
    private val calculate: (I) -> O,
    private val onComplete: (I, Result<O>) -> Unit,
) : AutoCloseable {
    private class Work<I>(var value: I)
    private val ownerThread = Thread.currentThread()
    private var running: Work<I>? = null
    private var pending: Work<I>? = null
    private var reusable: Work<I>? = null
    private var closed = false
    private var dispatchFailure: Throwable? = null

    fun submit(value: I) {
        check(Thread.currentThread() === ownerThread) { "Owner thread required" }
        val start = synchronized(this) {
            dispatchFailure?.let { throw IllegalStateException("Owner dispatcher failed; controller is closed", it) }
            check(!closed) { "Text controller closed" }
            if (running != null) {
                // Keep the pending slot (or the one invalidate just dropped) and only replace
                // its value: a superseded submission never runs, so its Work need not be reallocated.
                // Clear reusable on promotion: leaving it aliased would let the owner thread
                // rewrite Work.value while the background thread reads the running instance.
                val slot = pending ?: reusable?.also { pending = it; reusable = null }
                if (slot != null) slot.value = value else pending = Work(value)
                null
            } else {
                val work = reusable ?: Work(value)
                reusable = null
                work.value = value
                running = work
                work
            }
        }
        if (start != null) launch(start)
    }

    @Synchronized fun invalidate() {
        val dropped = pending
        pending = null
        if (dropped != null) reusable = dropped
    }

    private fun launch(work: Work<I>) {
        val submitting = AtomicBoolean(true)
        val started = AtomicBoolean(false)
        try {
            background.execute {
                if (Thread.currentThread() === ownerThread) {
                    val error = IllegalStateException("Background executor must run off the owner thread")
                    if (submitting.get()) throw error
                    failDispatch(error)
                    return@execute
                }
                started.set(true)
                val result = try { Result.success(calculate(work.value)) } catch (failure: Throwable) {
                    if (failure is Error) { failDispatch(failure); throw failure }
                    Result.failure(failure)
                }
                val delivering = AtomicBoolean(false)
                try {
                    owner.execute {
                        delivering.set(true)
                        if (Thread.currentThread() !== ownerThread) {
                            failDispatch(IllegalStateException("Owner executor ran on the wrong thread"))
                        } else complete(work, result)
                    }
                } catch (failure: Throwable) {
                    if (delivering.get()) throw failure
                    failDispatch(failure)
                    if (failure is Error) throw failure
                }
            }
        } catch (failure: Throwable) {
            if (started.get()) throw failure
            synchronized(this) { if (running === work) running = null }
            if (failure is Error) throw failure
            onComplete(work.value, Result.failure(failure))
        } finally { submitting.set(false) }
    }

    private fun complete(work: Work<I>, result: Result<O>) {
        val next: Work<I>?
        synchronized(this) {
            if (closed || running !== work) return
            running = null
            next = pending
            pending = null
            if (next != null) running = next
        }
        if (next != null) launch(next) else onComplete(work.value, result)
    }

    @Synchronized private fun failDispatch(failure: Throwable) {
        if (closed) return
        dispatchFailure = failure
        closed = true
        running = null
        pending = null
        reusable = null
    }

    @Synchronized override fun close() {
        closed = true
        running = null
        pending = null
        reusable = null
    }
}
