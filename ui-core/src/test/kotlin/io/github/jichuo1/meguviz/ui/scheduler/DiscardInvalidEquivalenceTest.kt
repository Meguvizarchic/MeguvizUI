package io.github.jichuo1.meguviz.ui.scheduler

import io.github.jichuo1.meguviz.ui.testkit.VirtualFrameDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Differential proof that the O(C) `discardInvalid` command sweep (live flag + one compaction)
 * is observably identical to the a6b8ba5 implementation, whose `queue.contains` doubled as the
 * reentry guard.
 *
 * Both schedulers run the same seeded program. Validators and actions re-enter the scheduler
 * (nested `discardInvalid`, `pause(true/false)`, `resume`, `close`, `recover`, new submissions,
 * validity flips, throwing) based only on a deterministic function of (seed, id, call number), so
 * any divergence in which validator runs, which entry is removed or what is counted shows up as
 * a transcript mismatch.
 */
class DiscardInvalidEquivalenceTest {

    private interface Target {
        fun submitCommand(isValid: () -> Boolean, action: () -> Unit): SubmitResult
        fun submitBatch(operations: List<ScheduledOperation>): SubmitResult
        fun submitState(slot: Any, isValid: () -> Boolean, action: () -> Unit): SubmitResult
        fun discardInvalid()
        fun pause(clear: Boolean)
        fun resume()
        fun recover()
        fun close()
        fun describe(): String
    }

    private class Current(private val s: UiScheduler) : Target {
        override fun submitCommand(isValid: () -> Boolean, action: () -> Unit) = s.submitCommand(isValid, action)
        override fun submitBatch(operations: List<ScheduledOperation>) = s.submitBatch(operations)
        override fun submitState(slot: Any, isValid: () -> Boolean, action: () -> Unit) = s.submitState(slot, isValid, action)
        override fun discardInvalid() = s.discardInvalid()
        override fun pause(clear: Boolean) = s.pause(clear)
        override fun resume() = s.resume()
        override fun recover() = s.recover()
        override fun close() = s.close()
        override fun describe() = "${s.state} ${s.counters} s=${s.pendingStateCount} c=${s.pendingCommandCount} f=${s.failure}"
    }

    private class Legacy(private val s: LegacyUiScheduler) : Target {
        override fun submitCommand(isValid: () -> Boolean, action: () -> Unit) = s.submitCommand(isValid, action)
        override fun submitBatch(operations: List<ScheduledOperation>) = s.submitBatch(operations)
        override fun submitState(slot: Any, isValid: () -> Boolean, action: () -> Unit) = s.submitState(slot, isValid, action)
        override fun discardInvalid() = s.discardInvalid()
        override fun pause(clear: Boolean) = s.pause(clear)
        override fun resume() = s.resume()
        override fun recover() = s.recover()
        override fun close() = s.close()
        override fun describe() = "${s.state} ${s.counters} s=${s.pendingStateCount} c=${s.pendingCommandCount} f=${s.failure}"
    }

    /** One simulated world; everything observable is appended to [log]. */
    private class World(val seed: Long, legacy: Boolean, capacity: Int) {
        val driver = VirtualFrameDriver()
        val log = StringBuilder()
        private val valid = HashMap<Int, Boolean>()
        private val calls = HashMap<Int, Int>()
        private var nextId = 0
        private var depth = 0
        val target: Target = if (legacy) {
            Legacy(LegacyUiScheduler(driver, {}, capacity, capacity, 5, nanoTime = { 0L }))
        } else {
            Current(UiScheduler(driver, {}, capacity, capacity, 5, nanoTime = { 0L }))
        }

        private fun roll(id: Int, salt: Int): Int {
            val n = calls.merge(id * 4 + salt, 1, Int::plus)!!
            var x = seed * 0x9E3779B97F4A7C15uL.toLong() + id * 31L + n * 1_000_003L + salt
            x = x xor (x ushr 33); x *= -0xae502812aa7333L; x = x xor (x ushr 29)
            return ((x ushr 1) % 100).toInt()
        }

        fun newId(isValid: Boolean): Int = nextId++.also { valid[it] = isValid }

        fun flip(id: Int) { if (id in valid) valid[id] = !valid.getValue(id) }

        fun invalidateAll() { valid.keys.forEach { valid[it] = false } }

        private fun reenter(id: Int, salt: Int, from: String) {
            if (depth >= 2) return
            depth++
            try {
                val r = roll(id, salt)
                val t = target
                when {
                    r < 10 -> { log.append(" ${from}nested"); t.discardInvalid() }
                    r < 13 -> { log.append(" ${from}pauseKeep"); t.pause(false) }
                    r < 15 -> { log.append(" ${from}pauseClear"); t.pause(true) }
                    r < 18 -> { log.append(" ${from}resume"); runCatching { t.resume() }.onFailure { log.append("!") } }
                    r < 19 -> { log.append(" ${from}close"); t.close() }
                    r < 21 -> { log.append(" ${from}recover"); runCatching { t.recover() }.onFailure { log.append("!") } }
                    r < 26 -> { val n = newId(true); log.append(" ${from}sub$n=${t.submitCommand(validator(n), action(n))}") }
                    r < 30 -> { val n = newId(true); log.append(" ${from}st$n=${t.submitState("r${n % 3}", validator(n), action(n))}") }
                    r < 40 -> { val other = (id + r) % maxOf(1, nextId); flip(other); log.append(" ${from}flip$other") }
                    r < 42 && from == "v" -> { log.append(" throw"); throw IllegalStateException("validator $id") }
                    // A frame delivered from inside a validator: contrived for Choreographer, but it
                    // is the one path that can observe the deques mid-sweep.
                    r < 45 && from == "v" -> { log.append(" tick"); runCatching { driver.tick() }.onFailure { log.append("!") } }
                }
            } finally {
                depth--
            }
        }

        fun validator(id: Int): () -> Boolean = {
            log.append(" v$id")
            reenter(id, 0, "v")
            valid.getValue(id)
        }

        fun action(id: Int): () -> Unit = {
            log.append(" a$id")
            reenter(id, 1, "a")
        }

        fun step(text: String, block: () -> Any?) {
            log.append("\n").append(text).append(':')
            val result = runCatching(block)
            log.append(" => ").append(result.getOrNull() ?: result.exceptionOrNull()?.javaClass?.name)
            log.append(" | ").append(target.describe()).append(" pending=").append(driver.pendingCount)
        }
    }

    private fun run(seed: Long, legacy: Boolean, capacity: Int, steps: Int): String {
        val world = World(seed, legacy, capacity)
        val program = Random(seed)
        repeat(steps) { stepIndex ->
            val t = world.target
            when (program.nextInt(100)) {
                in 0..24 -> {
                    val id = world.newId(program.nextInt(4) != 0)
                    world.step("$stepIndex cmd$id") { t.submitCommand(world.validator(id), world.action(id)) }
                }
                in 25..34 -> {
                    val ops = List(1 + program.nextInt(4)) {
                        val id = world.newId(program.nextInt(3) != 0)
                        ScheduledOperation("b$id", world.validator(id), world.action(id))
                    }
                    world.step("$stepIndex batch") { t.submitBatch(ops) }
                }
                in 35..49 -> {
                    val id = world.newId(program.nextInt(4) != 0)
                    val slot = StateSlot("g${program.nextInt(3)}", program.nextInt(4))
                    world.step("$stepIndex state$id@$slot") { t.submitState(slot, world.validator(id), world.action(id)) }
                }
                in 50..59 -> { val id = program.nextInt(64); world.step("$stepIndex flip$id") { world.flip(id) } }
                in 60..62 -> world.step("$stepIndex invalidateAll") { world.invalidateAll() }
                in 63..77 -> world.step("$stepIndex discard") { t.discardInvalid() }
                in 78..92 -> world.step("$stepIndex tick") { world.driver.tick() }
                in 93..94 -> world.step("$stepIndex pause") { t.pause(program.nextBoolean()) }
                in 95..96 -> world.step("$stepIndex resume") { t.resume() }
                else -> world.step("$stepIndex recover") { t.recover() }
            }
        }
        world.log.append("\nevents=").append(world.driver.events)
        return world.log.toString()
    }

    @Test fun reentrantProgramsMatchTheLegacySweepExactly() {
        var nested = 0
        var removedSomething = 0
        for (seed in 1L..600L) {
            val capacity = if (seed % 3 == 0L) 4 else 16
            val legacy = run(seed, legacy = true, capacity = capacity, steps = 120)
            val current = run(seed, legacy = false, capacity = capacity, steps = 120)
            assertEquals("seed $seed diverged", legacy, current)
            if ("vnested" in legacy) nested++
            if ("staleOperations=0," !in legacy.substringAfterLast("SchedulerCounters")) removedSomething++
        }
        // The programs must actually exercise the reentry guard, not just pass trivially.
        assertTrue("nested discard coverage $nested", nested > 100)
        assertTrue("stale coverage $removedSomething", removedSomething > 100)
    }

    @Test fun manySimultaneouslyStaleCommandsAreAllReleasedOnce() {
        for (legacy in listOf(true, false)) {
            val world = World(7, legacy, 512)
            val ids = List(256) { world.newId(true) }
            val ran = mutableListOf<Int>()
            ids.forEach { id -> world.target.submitCommand({ id % 2 == 0 }) { ran += id } }
            world.target.discardInvalid()
            assertTrue(world.target.describe(), world.target.describe().contains("staleOperations=128,"))
            assertTrue(world.target.describe(), world.target.describe().contains(" c=128 "))
            repeat(64) { world.driver.tick() }
            assertEquals(ids.filter { it % 2 == 0 }, ran)
            assertTrue(world.target.describe(), world.target.describe().contains("appliedOperations=128,"))
        }
    }
}
