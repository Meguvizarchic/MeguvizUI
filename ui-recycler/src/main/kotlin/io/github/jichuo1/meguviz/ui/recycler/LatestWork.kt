package io.github.jichuo1.meguviz.ui.recycler

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Owner-thread admission. A running calculation is never interrupted; only the latest result commits. */
internal class LatestWork<I, O>(
    private val background: Executor,
    private val owner: Executor,
    private val compute: (I) -> O,
    private val apply: (I, O) -> Unit,
    private val failed: (Throwable) -> Unit,
) : AutoCloseable {
    private class Work<I>(var value: I)
    private val ownerThread = Thread.currentThread()
    private var running: Work<I>? = null
    private var pending: Work<I>? = null
    private var closed = false
    private var dispatchFailure: Throwable? = null

    fun submit(value: I) {
        check(Thread.currentThread() === ownerThread) { "Owner thread required" }
        val start = synchronized(this) {
            dispatchFailure?.let { throw IllegalStateException("Owner dispatcher failed; controller is closed", it) }
            check(!closed) { "List controller closed" }
            if (running != null) {
                // Keep the pending slot and only replace its value: a superseded submission never
                // runs, so its Work (and anything the value retains) need not be allocated again.
                val slot = pending
                if (slot != null) slot.value = value else pending = Work(value)
                null
            } else {
                Work(value).also { running = it }
            }
        }
        if (start != null) launch(start)
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
                val result = try { Result.success(compute(work.value)) } catch (failure: Throwable) {
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
            failed(failure)
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
        if (next != null) launch(next) else result.fold(onSuccess = { apply(work.value, it) }, onFailure = failed)
    }

    @Synchronized private fun failDispatch(failure: Throwable) {
        if (closed) return
        dispatchFailure = failure
        closed = true
        running = null
        pending = null
    }

    @Synchronized override fun close() {
        closed = true
        running = null
        pending = null
    }
}
