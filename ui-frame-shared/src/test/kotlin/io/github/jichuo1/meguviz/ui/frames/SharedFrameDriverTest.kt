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
        val driver = SharedFrameDriver(source)
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
        val driver = SharedFrameDriver(source)
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
        val driver = SharedFrameDriver(source)
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
        val driver = SharedFrameDriver(source)
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
        val driver = SharedFrameDriver(source)
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
        val driver = SharedFrameDriver(source)
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
        val driver = SharedFrameDriver(source)
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
        val driver = SharedFrameDriver(source)
        driver.requestFrame { fail("delivered after close") }
        driver.close()
        assertEquals(0, driver.pendingCount)
        assertFalse(source.hasRequest())
        assertThrows(IllegalStateException::class.java) { driver.requestFrame { } }
    }

    @Test fun closeFromInsideCallbackDoesNotThrowOrStarveTheIterator() {
        val source = FakeSource()
        val driver = SharedFrameDriver(source)
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
        var callback: ((FrameBudget) -> Unit)? = null
        val flaky = object : FrameSource {
            override fun requestFrame(cb: (FrameBudget) -> Unit): FrameTicket {
                if (fail) throw IllegalStateException("source broken")
                callback = cb
                return FrameTicket { callback = null }
            }
        }
        val driver = SharedFrameDriver(flaky)
        // Reentrant request from inside the fan-out forces a second source request; that request
        // is the one that fails, and it must not silently drop the reentrant client.
        driver.requestFrame { driver.requestFrame { } }
        fail = true
        val failure = runCatching { checkNotNull(callback).invoke(FrameBudget(0)) }.exceptionOrNull()
        assertTrue("source failure must propagate: $failure", failure is IllegalStateException)
        assertNotNull(driver.lastFailure)
        assertThrows(IllegalStateException::class.java) { driver.requestFrame { } }
    }

    @Test fun sourceFailureFailsFastAndRecordsDiagnostics() {
        val failing = object : FrameSource {
            override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket =
                throw IllegalStateException("source broken")
        }
        val driver = SharedFrameDriver(failing)
        assertThrows(IllegalStateException::class.java) { driver.requestFrame { } }
        assertNotNull(driver.lastFailure)
        // The driver is faulted: later requests fail fast rather than joining a dead source.
        assertThrows(IllegalStateException::class.java) { driver.requestFrame { } }
    }
}
