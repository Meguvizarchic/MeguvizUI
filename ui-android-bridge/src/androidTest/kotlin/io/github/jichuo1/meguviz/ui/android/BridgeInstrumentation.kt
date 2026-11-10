package io.github.jichuo1.meguviz.ui.android

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Debug
import android.view.View
import io.github.jichuo1.meguviz.android.runtime.core.FrameDriver
import io.github.jichuo1.meguviz.android.runtime.core.FrameScheduler
import io.github.jichuo1.meguviz.android.runtime.platform.MainThreadDispatcher
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceSnapshot
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceState
import io.github.jichuo1.meguviz.ui.ComponentType
import io.github.jichuo1.meguviz.ui.CreationEnvironment
import io.github.jichuo1.meguviz.ui.PropertyBinding
import io.github.jichuo1.meguviz.ui.PropertySpec
import io.github.jichuo1.meguviz.ui.ResourceAuthority
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.ViewKey
import io.github.jichuo1.meguviz.ui.scheduler.FrameBudget

/**
 * On-device bridge contracts against the real main Looper and MainThreadDispatcher.
 *
 * With `-e probe true` it also prints relative allocation and time figures for the bridge hot
 * paths. Those figures are only comparable between two builds measured on the same device in the
 * same session, ideally from a non-debuggable build compiled ahead of time; they are not device
 * performance claims.
 */
class BridgeInstrumentation : Instrumentation() {
    private var arguments: Bundle? = null

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); this.arguments = arguments; start() }

    /** The platform frame source is the only controlled part; every other class is the shipped one. */
    private class ManualFrames(private val dispatcher: MainThreadDispatcher) : FrameDriver {
        private var frame: FrameDriver.Callback? = null
        private var nanos = 0L
        var requests = 0
        override fun request(callback: FrameDriver.Callback): Boolean {
            dispatcher.checkOwner()
            check(frame == null) { "Duplicate physical frame source" }
            requests++
            frame = callback
            return true
        }
        override fun remove(callback: FrameDriver.Callback) { dispatcher.checkOwner(); if (frame === callback) frame = null }
        fun hasFrame(): Boolean = frame != null
        fun tick() { take().onFrame(next()) }
        fun tickWithDeadline() { take().onFrame(next(), System.nanoTime() + 8_000_000L, nanos + 16_666_667L) }
        private fun next(): Long { nanos += 16_666_667L; return nanos }
        private fun take(): FrameDriver.Callback { val callback = checkNotNull(frame); frame = null; return callback }
    }

    private class Fixture(val instrumentation: Instrumentation, resources: Boolean, custom: ResourceAuthority? = null) : AutoCloseable {
        val dispatcher = MainThreadDispatcher()
        val frames = ManualFrames(dispatcher)
        val errors = mutableListOf<Throwable>()
        val upstream = FrameScheduler(dispatcher, frames, { errors += it }, 64)
        val driver = AndroidFrameDriver(dispatcher, upstream, Any())
        val state = ResourceState(dispatcher, "probe", ResourceSnapshot(320, 1f, "zh-CN", 0, 0, 360, 640, 0, "fixture"), 4)
        val authority = androidResourceAuthority(state)
        val session = UiSession(CreationEnvironment(instrumentation.targetContext, "probe", custom ?: if (resources) authority else null), driver)
        var writes = 0L

        fun bindings(count: Int, resourceSensitive: Boolean): List<PropertyBinding<View, Int>> {
            val spec = PropertySpec<View, Int>(
                id = "probe:value", coalescible = true, resourceSensitive = resourceSensitive,
                write = { _, value -> writes += value },
            )
            val component = ComponentType("probe", View::class.java)
            return List(count) { index ->
                session.bind(session.borrow(View(instrumentation.targetContext), ViewKey("view-$index"), component), spec)
            }
        }

        fun drain() { while (frames.hasFrame()) frames.tick() }

        override fun close() { session.close(); driver.close(); upstream.close(); state.close(); check(errors.isEmpty()) { errors.toString() } }
    }

    /** The message of the IllegalStateException [block] is refused with, or null if it ran. */
    private fun refused(block: () -> Unit): String? =
        try { block(); null } catch (failure: IllegalStateException) { failure.message ?: "" }

    override fun onStart() {
        val cases = linkedMapOf<String, () -> Unit>(
            "budget_source_and_deadline_follow_the_upstream_tick" to {
                Fixture(this, resources = true).use { fixture ->
                    val budgets = mutableListOf<FrameBudget>()
                    repeat(2) { fixture.driver.requestFrame { budgets += it }; fixture.frames.tick() }
                    repeat(2) { fixture.driver.requestFrame { budgets += it }; fixture.frames.tickWithDeadline() }
                    check(budgets.size == 4)
                    check(budgets[0].source == "meguviz-android.runtime:unknown" && budgets[1].source == budgets[0].source)
                    check(budgets[0].estimated && budgets[0].deadlineNanos == null)
                    check(budgets[2].source == "meguviz-android.runtime:platform-vsync" && budgets[3].source == budgets[2].source)
                    check(!budgets[2].estimated && budgets[2].deadlineNanos != null)
                    check(budgets[3].frameTimeNanos - budgets[2].frameTimeNanos == 16_666_667L)
                }
            },
            "resource_sensitive_work_follows_upstream_invalidation" to {
                Fixture(this, resources = true).use { fixture ->
                    val binding = fixture.bindings(1, resourceSensitive = true).single()
                    binding.submit(1); fixture.drain()
                    check(fixture.writes == 1L && binding.writes == 1L)
                    binding.submit(10); fixture.state.invalidateTheme(); fixture.drain()
                    check(fixture.writes == 1L) { "work captured before a theme change must not commit" }
                    binding.submit(100); fixture.drain()
                    check(fixture.writes == 101L)
                    binding.submit(1000); fixture.state.invalidateResources(); fixture.drain()
                    check(fixture.writes == 101L) { "work captured before a resource change must not commit" }
                    binding.submit(10_000); fixture.drain()
                    check(fixture.writes == 10_101L && binding.writes == 3L)
                }
            },
            "resource_authority_tokens_are_scoped_to_their_state" to {
                Fixture(this, resources = true).use { fixture ->
                    val other = ResourceState(fixture.dispatcher, "other", ResourceSnapshot(320, 1f, "zh-CN", 0, 0, 360, 640, 0, "fixture"), 4)
                    try {
                        val first = fixture.authority.capture()
                        check(fixture.authority.isCurrent(first) && fixture.authority.isCurrent(fixture.authority.capture()))
                        check(!androidResourceAuthority(other).isCurrent(first) && !fixture.authority.isCurrent(Any()))
                        fixture.state.invalidateTheme()
                        check(!fixture.authority.isCurrent(first))
                        val second = fixture.authority.capture()
                        check(second !== first && fixture.authority.isCurrent(second))
                        fixture.state.invalidateResources()
                        check(!fixture.authority.isCurrent(second) && fixture.authority.isCurrent(fixture.authority.capture()))
                    } finally { other.close() }
                }
            },
            "owner_checks_reject_another_thread" to {
                Fixture(this, resources = true).use { fixture ->
                    val binding = fixture.bindings(1, resourceSensitive = true).single()
                    val failures = arrayOfNulls<Throwable>(4)
                    val worker = Thread {
                        val attempts = listOf<() -> Unit>(
                            { fixture.session.assertOwner() }, { fixture.dispatcher.checkOwner() },
                            { fixture.authority.capture() }, { binding.submit(1) },
                        )
                        attempts.forEachIndexed { index, attempt -> try { attempt() } catch (failure: Throwable) { failures[index] = failure } }
                    }
                    worker.start(); worker.join(10_000)
                    check(!worker.isAlive && failures.all { it is IllegalStateException }) { failures.contentToString() }
                    check(fixture.dispatcher.isOwnerThread)
                    fixture.session.assertOwner()
                    fixture.drain()
                    check(fixture.writes == 0L)
                }
            },
            "binding_tokens_go_stale_with_exactly_what_they_captured" to {
                Fixture(this, resources = true).use { fixture ->
                    val component = ComponentType("probe", View::class.java)
                    val first = fixture.session.borrow(View(targetContext), ViewKey("first"), component)
                    val second = fixture.session.borrow(View(targetContext), ViewKey("second"), component)
                    val plain = first.token()
                    val themed = first.token(resources = true)
                    val other = second.token()
                    check(plain.isCurrent() && themed.isCurrent() && other.isCurrent())
                    check(plain !== themed && other !== plain)
                    fixture.state.invalidateTheme()
                    check(!themed.isCurrent() && plain.isCurrent()) { "an upstream theme change reaches resource tokens only" }
                    val afterTheme = first.token(resources = true)
                    check(afterTheme !== themed && afterTheme.isCurrent())
                    fixture.state.invalidateResources()
                    check(!afterTheme.isCurrent() && plain.isCurrent())
                    val afterResources = first.token(resources = true)
                    check(afterResources.isCurrent())
                    fixture.session.invalidateTheme()
                    check(!afterResources.isCurrent() && !themed.isCurrent() && plain.isCurrent()) { "a session theme change reaches resource tokens only" }
                    val afterSessionTheme = first.token(resources = true)
                    check(afterSessionTheme.isCurrent())
                    fixture.session.rebind(first)
                    check(!plain.isCurrent() && !afterSessionTheme.isCurrent() && other.isCurrent()) { "rebind is scoped to its node" }
                    val rebound = first.token()
                    check(rebound !== plain && rebound.isCurrent())
                    fixture.session.detach()
                    check(!rebound.isCurrent() && !other.isCurrent())
                    val whileDetached = first.token()
                    check(!whileDetached.isCurrent())
                    fixture.session.reattach()
                    check(!rebound.isCurrent() && !other.isCurrent() && !whileDetached.isCurrent()) { "reattach must not revive older tokens" }
                    val reattached = first.token()
                    check(reattached.isCurrent() && second.token().isCurrent())
                    fixture.session.release(first)
                    check(!reattached.isCurrent() && second.token().isCurrent())
                    check(refused { first.token() } == "View reference released")
                    val last = second.token()
                    fixture.session.close()
                    check(!last.isCurrent())
                }
            },
            "an_unchanged_capture_gets_the_same_token_back" to {
                Fixture(this, resources = true).use { fixture ->
                    val component = ComponentType("probe", View::class.java)
                    val first = fixture.session.borrow(View(targetContext), ViewKey("first"), component)
                    val second = fixture.session.borrow(View(targetContext), ViewKey("second"), component)
                    val plain = first.token()
                    val themed = first.token(resources = true)
                    // Reuse is per view and per kind, and ends with the first change either covers.
                    check(first.token() === plain && first.token(resources = true) === themed)
                    check(second.token() !== plain && second.token(resources = true) !== themed)
                    fixture.state.invalidateTheme()
                    val afterTheme = first.token(resources = true)
                    check(afterTheme !== themed && first.token(resources = true) === afterTheme && first.token() === plain)
                    val untouched = second.token()
                    fixture.session.rebind(first)
                    check(first.token() !== plain && first.token(resources = true) !== afterTheme && second.token() === untouched)
                }
            },
            "released_views_leave_the_session_and_close_runs_in_reverse_registration_order" to {
                Fixture(this, resources = false).use { fixture ->
                    val component = ComponentType("probe", View::class.java)
                    val released = mutableListOf<Int>()
                    val views = List(400) { View(targetContext) }
                    val refs = views.mapIndexed { index, view ->
                        fixture.session.borrow(view, ViewKey("node-$index"), component).also { ref ->
                            fixture.session.onRelease(ref) { released += index }
                        }
                    }
                    for (index in refs.indices step 3) fixture.session.release(refs[index])
                    check(released == (refs.indices step 3).toList())
                    refs.forEachIndexed { index, ref ->
                        if (index % 3 == 0) check(refused { ref.get() } == "View reference released")
                        else check(ref.get() === views[index])
                    }
                    check(refused { fixture.session.borrow(View(targetContext), ViewKey("node-1"), component) } == "Duplicate root key: ${ViewKey("node-1")}")
                    // A released key is free again, and the node registered last is released first.
                    val late = fixture.session.borrow(View(targetContext), ViewKey("node-0"), component)
                    fixture.session.onRelease(late) { released += refs.size }
                    released.clear()
                    fixture.session.close()
                    check(released == listOf(refs.size) + refs.indices.filter { it % 3 != 0 }.reversed()) { released.toString() }
                }
            },
            "an_authority_with_a_new_token_per_capture_is_still_asked_every_time" to {
                class Stamp(val epoch: Int)
                var epoch = 0
                var captures = 0
                val custom = ResourceAuthority(
                    capture = { captures++; Stamp(epoch) },
                    isCurrent = { token -> token is Stamp && token.epoch == epoch },
                )
                Fixture(this, resources = true, custom = custom).use { fixture ->
                    val ref = fixture.session.borrow(View(targetContext), ViewKey("custom"), ComponentType("probe", View::class.java))
                    val first = ref.token(resources = true)
                    val second = ref.token(resources = true)
                    check(captures == 2 && first !== second && first.isCurrent() && second.isCurrent())
                    ref.token()
                    check(captures == 2) { "plain tokens do not consult the authority" }
                    val binding = fixture.bindings(1, resourceSensitive = true).single()
                    binding.submit(1); binding.submit(2); fixture.drain()
                    check(fixture.writes == 2L && binding.writes == 1L) { "two submits in one frame commit the later value once" }
                    binding.submit(10); epoch++; fixture.drain()
                    check(fixture.writes == 2L && !first.isCurrent() && !second.isCurrent())
                    binding.submit(100); fixture.drain()
                    check(fixture.writes == 102L && binding.writes == 2L && captures == 6)
                }
            },
        )
        var failures = 0
        cases.entries.forEachIndexed { index, (name, body) ->
            fun status() = Bundle().apply {
                putString("class", BridgeInstrumentation::class.java.name)
                putString("test", name)
                putInt("numtests", cases.size)
                putInt("current", index + 1)
            }
            sendStatus(1, status())
            var failure: Throwable? = null
            runOnMainSync { try { body() } catch (error: Throwable) { failure = error } }
            val end = status()
            failure?.let { failures++; end.putString("stack", it.stackTraceToString()) }
            sendStatus(if (failure == null) 0 else -2, end)
        }
        val report = StringBuilder("Android bridge: ${cases.size} tests, $failures failures\n")
        if (failures == 0 && arguments?.getString("probe") == "true") {
            var failure: Throwable? = null
            runOnMainSync { try { probe(report) } catch (error: Throwable) { failure = error } }
            failure?.let { failures++; report.append(it.stackTraceToString()) }
        }
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", report.toString()) })
    }

    // ---- Opt-in relative measurement ---------------------------------------------------------------

    private fun allocatedBytes(): Long = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()

    /** Prints `probe,<name>,<round>,<operations>,<nanos per operation>,<bytes per operation>`. */
    private fun measure(report: StringBuilder, name: String, operations: Int, rounds: Int, warmup: Int, round: () -> Unit) {
        repeat(warmup) { round() }
        repeat(rounds) { index ->
            val bytes = allocatedBytes()
            val start = System.nanoTime()
            round()
            val nanos = System.nanoTime() - start
            val allocated = allocatedBytes() - bytes
            report.append("probe,").append(name).append(',').append(index).append(',').append(operations).append(',')
                .append(nanos.toDouble() / operations).append(',').append(allocated.toDouble() / operations).append('\n')
        }
    }

    private fun probe(report: StringBuilder) {
        val rounds = arguments?.getString("probeRounds")?.toInt() ?: 15
        val warmup = arguments?.getString("probeWarmup")?.toInt() ?: 40
        Fixture(this, resources = true).use { fixture ->
            var frames = 0
            val callback: (FrameBudget) -> Unit = { frames++ }
            measure(report, "frame_unknown_deadline", 2_000, rounds, warmup) {
                repeat(2_000) { fixture.driver.requestFrame(callback); fixture.frames.tick() }
            }
            measure(report, "frame_platform_deadline", 2_000, rounds, warmup) {
                repeat(2_000) { fixture.driver.requestFrame(callback); fixture.frames.tickWithDeadline() }
            }
            var live = 0
            measure(report, "authority_capture", 10_000, rounds, warmup) {
                repeat(10_000) { if (fixture.authority.isCurrent(fixture.authority.capture())) live++ }
            }
            val token = fixture.authority.capture()
            measure(report, "authority_is_current", 10_000, rounds, warmup) {
                repeat(10_000) { if (fixture.authority.isCurrent(token)) live++ }
            }
            measure(report, "ui_owner_check", 100_000, rounds, warmup) {
                repeat(100_000) { fixture.session.assertOwner() }
            }
            measure(report, "android_owner_check", 100_000, rounds, warmup) {
                repeat(100_000) { fixture.dispatcher.checkOwner() }
            }
            val component = ComponentType("probe", View::class.java)
            val refs = List(256) { index -> fixture.session.borrow(View(targetContext), ViewKey("token-$index"), component) }
            measure(report, "token_upstream_resources", 40 * refs.size, rounds, warmup) {
                repeat(40) { for (ref in refs) if (ref.token(resources = true).isCurrent()) live++ }
            }
            measure(report, "token_no_resources", 40 * refs.size, rounds, warmup) {
                repeat(40) { for (ref in refs) if (ref.token().isCurrent()) live++ }
            }
            var resolved = 0
            measure(report, "ref_get", 40 * refs.size, rounds, warmup) {
                repeat(40) { for (ref in refs) if (ref.get().parent == null) resolved++ }
            }
            check(frames > 0 && live > 0 && resolved > 0)
        }
        updates(report, "update_upstream_resources", authority = true, resourceSensitive = true, rounds, warmup)
        updates(report, "update_local_resources", authority = false, resourceSensitive = true, rounds, warmup)
        updates(report, "update_no_resources", authority = true, resourceSensitive = false, rounds, warmup)
    }

    /** Rounds of 16 frames; each frame submits and commits 256 coalescible property updates. */
    private fun updates(report: StringBuilder, name: String, authority: Boolean, resourceSensitive: Boolean, rounds: Int, warmup: Int) {
        Fixture(this, resources = authority).use { fixture ->
            val bindings = fixture.bindings(256, resourceSensitive)
            var value = 0
            var expected = 0L
            measure(report, name, 16 * bindings.size, rounds, warmup) {
                repeat(16) {
                    value++
                    for (binding in bindings) binding.submit(value)
                    fixture.drain()
                    expected += value.toLong() * bindings.size
                }
            }
            check(fixture.writes == expected) { "$name committed ${fixture.writes}, expected $expected" }
        }
    }
}
