package io.github.jichuo1.meguviz.ui.scheduler

import io.github.jichuo1.meguviz.ui.testkit.VirtualFrameDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the constructor shapes that code compiled against the published 1.0.0 links to.
 *
 * The public API baseline is a snapshot of the current build, so it cannot notice when a shape
 * that 1.0.0 consumers were compiled against disappears. A Kotlin caller that omits trailing
 * arguments is compiled to the synthetic default-argument constructor, not to the declared one;
 * adding a parameter to the primary constructor silently drops that synthetic and the old binary
 * fails with `NoSuchMethodError` at the first `UiScheduler(driver, assertOwner)`.
 */
class LegacyBinaryShapeTest {

    private val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
    private val function0 = Function0::class.java

    @Test
    fun theSevenArgumentConstructorStillExists() {
        val constructor = UiScheduler::class.java.getConstructor(
            UiFrameDriver::class.java, function0, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, function0,
        )
        val scheduler = constructor.newInstance(VirtualFrameDriver(), {}, 3, 4, 5, 6L, { 0L }) as UiScheduler
        assertEquals(3, scheduler.stateCapacity)
        assertEquals(4, scheduler.commandCapacity)
        assertEquals(5, scheduler.maxOperationsPerFrame)
        assertEquals(6L, scheduler.maxFrameWorkNanos)
    }

    @Test
    fun theKotlinDefaultArgumentConstructorOf100StillExistsAndKeepsTheDefaults() {
        val constructor = UiScheduler::class.java.getConstructor(
            UiFrameDriver::class.java, function0, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, function0,
            Int::class.javaPrimitiveType, marker,
        )
        // Bits 2..6 set: stateCapacity, commandCapacity, maxOperationsPerFrame, maxFrameWorkNanos and
        // nanoTime all come from their defaults, exactly like `UiScheduler(driver, assertOwner)`.
        val allDefaults = (1 shl 2) or (1 shl 3) or (1 shl 4) or (1 shl 5) or (1 shl 6)
        val legacy = constructor.newInstance(VirtualFrameDriver(), {}, 0, 0, 0, 0L, null, allDefaults, null) as UiScheduler
        val current = UiScheduler(VirtualFrameDriver(), {})

        assertEquals(current.stateCapacity, legacy.stateCapacity)
        assertEquals(current.commandCapacity, legacy.commandCapacity)
        assertEquals(current.maxOperationsPerFrame, legacy.maxOperationsPerFrame)
        assertEquals(current.maxFrameWorkNanos, legacy.maxFrameWorkNanos)
        assertEquals(256, legacy.stateCapacity)
        assertEquals(2_000_000L, legacy.maxFrameWorkNanos)
    }

    @Test
    fun aLegacyConstructedSchedulerStaysOwnerThreadOnly() {
        val scheduler = UiScheduler(VirtualFrameDriver(), {})
        val result = scheduler.submitCommandAsync { }
        assertTrue("no dispatcher means async entry points report CLOSED; was $result", result is SubmitResult.Rejected)
    }
}
