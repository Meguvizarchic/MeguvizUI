package io.github.jichuo1.meguviz.ui.frames

import io.github.jichuo1.meguviz.ui.scheduler.FrameBudget
import io.github.jichuo1.meguviz.ui.scheduler.FrameTicket
import org.junit.Assert.*
import org.junit.Test

class SharedFrameDriverTest {
    /** Manual source: records requests and lets the test emit exactly one frame. */
    private class FakeSource : FrameSource {
        var requests = 0
        var cancels = 0
        private var callback: ((FrameBudget) -> Unit)? = null

        override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
            check(this.callback == null) { "duplicate source request" }
            requests++
            this.callback = callback
            return FrameTicket {
                cancels++
                if (this.callback === callback) this.callback = null
            }
        }

        fun emit(budget: FrameBudget = FrameBudget(0)) {
            val current = checkNotNull(callback) { "no pending source request" }
            callback = null
            current(budget)
        }

        fun hasRequest(): Boolean = callback != null
    }

    @Test fun coalescesManyClientsIntoOneSourceRequest() {
        val source = FakeSource()
        val driver = SharedFrameDriver(source, ownerCheck = {})
        val seen = mutableListOf<Int>()
        repeat(3) { index -> driver.requestFrame { seen += index } }
        assertEquals(1, source.requests)
        assertEquals(3, driver.pendingCount)
        source.emit()
        assertEquals(listOf(0, 1, 2), seen)
        assertEquals(0, driver.pendingCount)
        assertFalse(source.hasRequest())
    }

    @Test fun staleTicketCannotCancelALaterRequest() {
        val source = FakeSource()
        val driver = SharedFrameDriver(source, ownerCheck = {})
        val seen = mutableListOf<String>()
        val stale = driver.requestFrame { seen += "first" }
        source.emit()
        driver.requestFrame { seen += "second" }
        stale.cancel()
        assertEquals(0, source.cancels)
        source.emit()
        assertEquals(listOf("first", "second"), seen)
    }

    @Test fun cancellingAllPendingCancelsTheSourceRequest() {
        val source = FakeSource()
        val driver = SharedFrameDriver(source, ownerCheck = {})
        val first = driver.requestFrame { fail("cancelled") }
        val second = driver.requestFrame { fail("cancelled") }
        first.cancel()
        assertTrue(source.hasRequest())
        second.cancel()
        assertFalse(source.hasRequest())
        assertEquals(1, source.cancels)
        assertEquals(0, driver.pendingCount)
    }

    @Test fun oneClientFailureDoesNotBlockTheOthers() {
        val source = FakeSource()
        val driver = SharedFrameDriver(source, ownerCheck = {})
        val seen = mutableListOf<String>()
        driver.requestFrame { error("boom") }
        driver.requestFrame { seen += "second" }
        source.emit()
        assertEquals(listOf("second"), seen)
        assertNotNull(driver.takeFailure())
        assertNull(driver.takeFailure())
    }

    @Test fun errorIsRethrownAfterFanOut() {
        val source = FakeSource()
        val driver = SharedFrameDriver(source, ownerCheck = {})
        val seen = mutableListOf<String>()
        driver.requestFrame { throw AssertionError("boom") }
        driver.requestFrame { seen += "second" }
        val failure = runCatching { source.emit() }.exceptionOrNull()
        assertTrue("Error must be rethrown after the fan-out", failure is AssertionError)
        assertEquals(listOf("second"), seen)
        assertNotNull(driver.takeFailure())
    }

    @Test fun reentrantRequestWaitsForTheNextFrame() {
        val source = FakeSource()
        val driver = SharedFrameDriver(source, ownerCheck = {})
        val seen = mutableListOf<String>()
        driver.requestFrame {
            seen += "outer"
            driver.requestFrame { seen += "inner" }
        }
        source.emit()
        assertEquals(listOf("outer"), seen)
        assertTrue(source.hasRequest())
        source.emit()
        assertEquals(listOf("outer", "inner"), seen)
    }

    @Test fun cancelFromInsideCallbackSkipsThatClient() {
        val source = FakeSource()
        val driver = SharedFrameDriver(source, ownerCheck = {})
        val seen = mutableListOf<String>()
        var second: FrameTicket? = null
        driver.requestFrame {
            seen += "first"
            second?.cancel()
        }
        second = driver.requestFrame { seen += "second" }
        source.emit()
        assertEquals(listOf("first"), seen)
    }

    @Test fun closeDropsPendingWithoutDelivering() {
        val source = FakeSource()
        val driver = SharedFrameDriver(source, ownerCheck = {})
        driver.requestFrame { fail("delivered after close") }
        driver.close()
        assertEquals(0, driver.pendingCount)
        assertFalse(source.hasRequest())
        assertThrows(IllegalStateException::class.java) { driver.requestFrame { } }
    }

    @Test fun closeFromInsideCallbackDoesNotThrowOrStarveTheIterator() {
        val source = FakeSource()
        val driver = SharedFrameDriver(source, ownerCheck = {})
        val seen = mutableListOf<String>()
        driver.requestFrame {
            seen += "first"
            driver.close()
        }
        driver.requestFrame { seen += "second" }
        // Must not throw ConcurrentModificationException into the platform callback.
        source.emit()
        assertEquals(listOf("first"), seen)
        assertEquals(0, driver.pendingCount)
    }

    @Test fun sourceFailureKeepsPendingRequestsAndRecordsDiagnostics() {
        var fail = false
        var pending: ((FrameBudget) -> Unit)? = null
        val flaky = object : FrameSource {
            override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
                if (fail) throw IllegalStateException("source broken")
                pending = callback
                return FrameTicket { pending = null }
            }
        }
        val driver = SharedFrameDriver(flaky, ownerCheck = {})
        // Reentrant request from inside the fan-out forces a second source request; that request
        // is the one that fails, and it must not silently drop the reentrant client.
        driver.requestFrame { driver.requestFrame { } }
        fail = true
        val failure = runCatching { checkNotNull(pending).invoke(FrameBudget(0)) }.exceptionOrNull()
        assertTrue("source failure must propagate: $failure", failure is IllegalStateException)
        assertNotNull(driver.lastFailure)
        // The accepted request survives the failure, so the next requestFrame retries the source
        // instead of the driver becoming a permanent dead end.
        assertEquals(1, driver.pendingCount)
    }

    @Test fun sourceFailureIsRetriedByTheNextRequest() {
        var fail = true
        var pending: ((FrameBudget) -> Unit)? = null
        val flaky = object : FrameSource {
            override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
                if (fail) throw IllegalStateException("source broken")
                pending = callback
                return FrameTicket { pending = null }
            }
        }
        val driver = SharedFrameDriver(flaky, ownerCheck = {})
        assertThrows(IllegalStateException::class.java) { driver.requestFrame { } }
        assertNotNull(driver.lastFailure)
        // The request accepted before the failure is still pending, not silently dropped.
        assertEquals(1, driver.pendingCount)
        fail = false
        val seen = mutableListOf<String>()
        driver.requestFrame { seen += "recovered" }
        assertEquals(2, driver.pendingCount)
        checkNotNull(pending).invoke(FrameBudget(0))
        assertEquals(listOf("recovered"), seen)
        assertEquals(0, driver.pendingCount)
    }

    @Test fun synchronousSourceIsRejectedInsteadOfRecursing() {
        val synchronous = object : FrameSource {
            override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
                callback(FrameBudget(0))
                return FrameTicket { }
            }
        }
        val driver = SharedFrameDriver(synchronous, ownerCheck = {})
        val failure = runCatching { driver.requestFrame { } }.exceptionOrNull()
        assertTrue("synchronous delivery must be a contract failure: $failure", failure is IllegalStateException)
        assertTrue(
            "message must name the async contract: ${failure?.message}",
            failure?.message?.contains("asynchronously") == true,
        )
    }

    @Test fun secondDeliveryForOneRequestIsRejected() {
        var pending: ((FrameBudget) -> Unit)? = null
        val manual = object : FrameSource {
            override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
                pending = callback
                return FrameTicket { pending = null }
            }
        }
        val driver = SharedFrameDriver(manual, ownerCheck = {})
        val seen = mutableListOf<String>()
        driver.requestFrame { seen += "once" }
        val deliver = checkNotNull(pending)
        deliver(FrameBudget(0))
        assertEquals(listOf("once"), seen)
        // A source that delivers one request twice would swap a map the outer fan-out is iterating.
        assertThrows(IllegalStateException::class.java) { deliver(FrameBudget(0)) }
        assertEquals(listOf("once"), seen)
    }

    @Test fun capacityRejectsBeyondMaxClients() {
        val source = FakeSource()
        val driver = SharedFrameDriver(source, ownerCheck = {})
        repeat(256) { driver.requestFrame { } }
        assertEquals(256, driver.pendingCount)
        assertEquals(1, source.requests)
        assertThrows(IllegalStateException::class.java) { driver.requestFrame { } }
        assertEquals(256, driver.pendingCount)
    }

    @Test fun closeIsIdempotentAndCancelsTheSourceRequest() {
        val source = FakeSource()
        val driver = SharedFrameDriver(source, ownerCheck = {})
        driver.requestFrame { fail("delivered after close") }
        driver.close()
        driver.close()
        assertEquals(0, driver.pendingCount)
        assertFalse(source.hasRequest())
        assertEquals(1, source.cancels)
    }

    @Test fun cancellationFailureDoesNotOverwriteAClientFailure() {
        var pending: ((FrameBudget) -> Unit)? = null
        val source = object : FrameSource {
            override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
                pending = callback
                return FrameTicket { throw IllegalStateException("cancel broken") }
            }
        }
        val driver = SharedFrameDriver(source, ownerCheck = {})
        driver.requestFrame { error("root cause") }
        checkNotNull(pending).invoke(FrameBudget(0))
        driver.close()
        // The client's Error text is the root cause; a cancellation failure must not replace it.
        assertTrue("root cause must survive: ${driver.lastFailure}",
            driver.lastFailure?.contains("root cause") == true)
    }
}
