package io.github.jichuo1.meguviz.ui

import android.os.Handler
import android.os.Looper
import android.util.LongSparseArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import io.github.jichuo1.meguviz.ui.scheduler.ChoreographerFrameDriver
import io.github.jichuo1.meguviz.ui.scheduler.UiFrameDriver
import io.github.jichuo1.meguviz.ui.scheduler.UiScheduler
import io.github.jichuo1.meguviz.ui.scheduler.SchedulerState
import io.github.jichuo1.meguviz.ui.scheduler.SubmitResult
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class SessionState { ACTIVE, DETACHED, DISPOSED }

class CloseRequest internal constructor() {
    @Volatile var completed: Boolean = false; internal set
    @Volatile var failure: Throwable? = null; internal set
}

/** One owner thread, one immutable frame driver, no global View or Context registry. */
class UiSession(
    environment: CreationEnvironment,
    driver: UiFrameDriver = ChoreographerFrameDriver(),
) : AutoCloseable {
    private var environmentValue: CreationEnvironment? = environment
    val environment: CreationEnvironment get() { assertOwner(); return checkNotNull(environmentValue) { "Session disposed: this UiSession is closed (close() was called, for example because its Activity was destroyed), so its creation environment is gone. Create a new session for the new Activity instead of reusing this one." } }
    private val handler = Handler(Looper.getMainLooper())
    /**
     * The main Looper's thread. A thread has at most one Looper and the main Looper never changes,
     * so comparing threads is the same test as comparing `Looper.myLooper()` with the main Looper,
     * without the thread-local lookup and the synchronized `Looper.getMainLooper()` per check.
     */
    private val ownerThread: Thread = handler.looper.thread
    private val closeRequested = AtomicBoolean(false)
    private val closeReceipt = CloseRequest()
    private val attachDomain = EpochDomain()
    private val themeDomain = EpochDomain()
    private val resourceDomain = EpochDomain()
    private val shared = Shared(this, closeRequested, attachDomain, themeDomain, resourceDomain, environment.resourceAuthority)
    private var nextIdentity = 1L
    private var releaseDepth = 0
    /**
     * Greater than zero while a rebind/release batch is running, so per-binding closes skip their
     * own `discardInvalid()` and the batch scans the pending queues once. Releasing a subtree of B
     * bindings costs one scan instead of B+1.
     */
    private var discardBatch = 0

    /** Runs [block] with the per-binding discard scan suppressed; the batch scans once itself. */
    private fun <T> batchedDiscard(block: () -> T): T {
        discardBatch++
        try { return block() } finally { discardBatch-- }
    }

    /**
     * [PropertyBinding.close] discards its queued work here. Inside a rebind/release batch the
     * batch performs the single scan, so closing many bindings does not rescan the queues per
     * binding.
     */
    internal fun discardForBindingClose() {
        if (discardBatch > 0) return
        scheduler.discardInvalid()
    }

    /**
     * Keyed by the primitive identity, so a lookup does not box it. Identities only grow, which
     * makes key order the registration order that [close] walks backwards.
     */
    private val nodes = LongSparseArray<Node>()
    private val parentKeys = IdentityHashMap<ViewGroup, MutableSet<ViewKey>>()
    private val views = IdentityHashMap<View, Long>()
    // Keyed by the parent View (not its identity) so children attached to a parent that is
    // borrowed/transferred into the session later are still found when that parent is released.
    private val children = IdentityHashMap<View, MutableSet<Long>>()
    private val sessionCleanup = mutableListOf<() -> Unit>()
    private class Node(
        val view: View,
        val key: ViewKey,
        val component: ComponentType<*>,
        val parent: ViewGroup?,
        val ownership: NativeOwnership,
        val binding: EpochDomain = EpochDomain(),
        val cleanup: MutableList<() -> Unit> = mutableListOf(),
        val properties: MutableSet<String> = mutableSetOf(),
    ) {
        /** Last token per kind, reused by [token] while a new capture would be identical. */
        var plainToken: BindingToken? = null
        var resourceToken: BindingToken? = null
    }

    /**
     * What every capture of this session reads. One shared object keeps a capture the size of the
     * closure it replaces, and neither class reaches into the session's private state.
     * [authority] is cleared with the creation environment when the session closes.
     */
    private class Shared(
        val session: UiSession,
        val closeRequested: AtomicBoolean,
        val attach: EpochDomain,
        val theme: EpochDomain,
        val resource: EpochDomain,
        var authority: ResourceAuthority?,
    )

    /**
     * One capture of everything a [BindingToken] depends on, and its validity function. [theme] and
     * [localResource] are null for a capture that does not cover resources.
     *
     * It holds the node's epoch domain rather than the node, so a token kept after release does not
     * keep the released View reachable. The node leaves [nodes] and its domain advances in the same
     * step ([releaseNode]) and identities are never reused, so comparing against the domain gives
     * the same answer as looking the node up again.
     */
    private class Capture(
        private val shared: Shared,
        private val domain: EpochDomain,
        val binding: EpochToken,
        val attach: EpochToken,
        val theme: EpochToken?,
        val localResource: EpochToken?,
        val upstream: Any?,
    ) : () -> Boolean {
        override fun invoke(): Boolean {
            val session = shared.session
            session.assertOwner()
            return session.state == SessionState.ACTIVE && !shared.closeRequested.get() &&
                domain.token() == binding && shared.attach.token() == attach &&
                (theme == null || (shared.theme.token() == theme &&
                    if (upstream != null) shared.authority?.isCurrent?.invoke(upstream) == true else shared.resource.token() == localResource))
        }
    }
    var state = SessionState.ACTIVE; private set
    /** The main handler doubles as the owner-thread dispatcher for the async submission entry points. */
    val scheduler = UiScheduler(driver, ::assertOwner, dispatcher = { runnable -> handler.post(runnable) })

    init { assertOwner() }

    fun assertOwner() { check(Thread.currentThread() === ownerThread) { "UI operation requires the main thread" } }
    private fun requireActive() { assertOwner(); check(state == SessionState.ACTIVE && !closeRequested.get() && scheduler.state == SchedulerState.ACTIVE) { "Session is not active" } }

    fun <P : ViewGroup, LP : ViewGroup.LayoutParams, V : View> mount(
        parent: P, policy: ParentPolicy<P, LP>, key: ViewKey, component: ComponentType<V>,
        params: LP, factory: ViewFactory<V>, configure: V.() -> Unit = {},
    ): ViewRef<V> {
        requireActive()
        reserve(parent, key)
        var view: V? = null
        var acquired = false
        try {
            view = factory.create(environment)
            require(component.viewClass.isInstance(view)) { "Factory type differs from component" }
            check(view.parent == null) { "Factory returned an already parented View" }
            check(!views.containsKey(view)) { "View already managed: this exact View instance (${view.javaClass.name}) is already mounted or borrowed by this session. Mount a View once; release its ref first with session.release(ref), or use a separate View." }
            acquired = true
            view.configure()
            requireActive()
            policy.attach(parent, view, params)
            return register(view, parent, key, component, NativeOwnership.CREATED)
        } catch (failure: Throwable) {
            if (acquired && view != null && view.parent === parent) parent.removeView(view)
            unreserve(parent, key)
            throw failure
        }
    }

    fun <V : View> borrow(view: V, key: ViewKey, component: ComponentType<V>): ViewRef<V> {
        requireActive()
        check(!views.containsKey(view)) { "View already managed: this exact View instance (${view.javaClass.name}) is already mounted or borrowed by this session. Mount a View once; release its ref first with session.release(ref), or use a separate View." }
        require(component.viewClass.isInstance(view))
        val parent = view.parent as? ViewGroup
        if (parent != null) reserve(parent, key)
        else check(!hasRootKey(key)) { "Duplicate root key: $key" }
        return register(view, parent, key, component, NativeOwnership.BORROWED)
    }

    fun <P : ViewGroup, LP : ViewGroup.LayoutParams, V : View> transfer(
        view: V, parent: P, policy: ParentPolicy<P, LP>, params: LP, key: ViewKey, component: ComponentType<V>,
    ): ViewRef<V> {
        requireActive()
        require(view.parent == null) { "Explicitly detach before transferring; UI never steals an external child" }
        require(component.viewClass.isInstance(view))
        check(!views.containsKey(view))
        reserve(parent, key)
        try {
            policy.attach(parent, view, params)
            return register(view, parent, key, component, NativeOwnership.TRANSFERRED)
        } catch (failure: Throwable) {
            if (view.parent === parent) parent.removeView(view)
            unreserve(parent, key)
            throw failure
        }
    }

    /** Uses the caller's inflater/Factory2 and real parent. Managed merge roots are rejected. */
    fun inflate(inflater: LayoutInflater, resource: Int, parent: ViewGroup, key: ViewKey): ViewRef<View> {
        requireActive()
        reserve(parent, key)
        var view: View? = null
        var acquired = false
        try {
            inflater.context.resources.getLayout(resource).use { parser ->
                while (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG && parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) parser.next()
                require(parser.name != "merge") { "Managed <merge> requires an explicit ownership protocol" }
            }
            view = inflater.inflate(resource, parent, false)
            check(view.parent == null && !views.containsKey(view)) { "Inflater returned an already owned View" }
            acquired = true
            requireActive()
            parent.addView(view)
            return register(view, parent, key, ComponentType("xml:$resource", View::class.java), NativeOwnership.CREATED)
        } catch (failure: Throwable) {
            if (acquired && view != null && view.parent === parent) parent.removeView(view)
            unreserve(parent, key)
            throw failure
        }
    }

    private fun hasRootKey(key: ViewKey): Boolean {
        for (index in 0 until nodes.size()) {
            val node = nodes.valueAt(index)
            if (node.parent == null && node.key == key) return true
        }
        return false
    }
    private fun reserve(parent: ViewGroup, key: ViewKey) {
        check(parentKeys.getOrPut(parent) { mutableSetOf() }.add(key)) { "Duplicate key in parent: ${key.value}" }
    }
    private fun unreserve(parent: ViewGroup, key: ViewKey) { parentKeys[parent]?.let { it.remove(key); if (it.isEmpty()) parentKeys.remove(parent) } }
    private fun <V : View> register(view: V, parent: ViewGroup?, key: ViewKey, component: ComponentType<V>, ownership: NativeOwnership): ViewRef<V> {
        requireActive()
        check(nextIdentity != Long.MAX_VALUE) { "Session node identities exhausted; create a new session" }
        val identity = nextIdentity++
        nodes.put(identity, Node(view, key, component, parent, ownership))
        views[view] = identity
        parent?.let { p -> children.getOrPut(p) { mutableSetOf() }.add(identity) }
        return ViewRef(this, identity, key, component)
    }
    /** The owner, session, disposal and release checks every reference use starts with; one lookup. */
    private fun nodeOf(ref: ViewRef<*>): Node {
        assertOwner(); check(ref.session === this); check(state != SessionState.DISPOSED)
        return checkNotNull(nodes[ref.identity]) { "View reference released" }
    }
    internal fun <V : View> resolve(ref: ViewRef<V>): V = requireNotNull(ref.component.viewClass.cast(nodeOf(ref).view))

    /**
     * Captures the current binding, attach, theme and resource identity of [ref].
     *
     * Every call still reads each of them, and still asks the upstream authority for its token.
     * When all of them are the very objects the node's previous token holds, a new token would be
     * indistinguishable from it, so that one is returned instead of allocating another. Epoch
     * tokens are cached per domain until it advances; an upstream authority takes part only if it
     * returns the same token object while nothing changed, and one that returns a new object per
     * call gets a new token per call as before.
     */
    fun <V : View> token(ref: ViewRef<V>, resources: Boolean = false): BindingToken {
        val node = nodeOf(ref)
        val binding = node.binding.token()
        val attach = attachDomain.token()
        val theme = if (resources) themeDomain.token() else null
        val localResource = if (resources) resourceDomain.token() else null
        val upstream = if (resources) environment.resourceAuthority?.capture?.invoke() else null
        val previous = if (resources) node.resourceToken else node.plainToken
        if (previous != null) {
            val captured = previous.current as Capture
            if (captured.binding === binding && captured.attach === attach && captured.theme === theme &&
                captured.localResource === localResource && captured.upstream === upstream) return previous
        }
        val token = BindingToken(Capture(shared, node.binding, binding, attach, theme, localResource, upstream))
        if (resources) node.resourceToken = token else node.plainToken = token
        return token
    }

    fun <V : View, T> bind(ref: ViewRef<V>, property: PropertySpec<V, T>): PropertyBinding<V, T> {
        requireActive()
        val node = nodeOf(ref)
        check(node.properties.add(property.id)) { "Property already owned: ${property.id}" }
        val binding = PropertyBinding(ref, property) { nodes[ref.identity]?.properties?.remove(property.id) }
        node.cleanup += { binding.close() }
        return binding
    }

    /** Reserve properties controlled by Lumen/animations before installing UI bindings. */
    fun <V : View> reserveProperties(ref: ViewRef<V>, ids: Set<String>): AutoCloseable {
        requireActive()
        val node = nodeOf(ref)
        check(ids.none { it in node.properties }) { "Conflicting visual property owner" }
        node.properties.addAll(ids)
        var closed = false
        val release = { assertOwner(); if (!closed) { closed = true; nodes[ref.identity]?.properties?.removeAll(ids) }; Unit }
        node.cleanup += release
        return AutoCloseable(release)
    }

    fun <V : View> onRelease(ref: ViewRef<V>, cleanup: () -> Unit) { requireActive(); nodeOf(ref).cleanup += cleanup }
    fun onClose(cleanup: () -> Unit) { requireActive(); sessionCleanup += cleanup }

    /** Caller explicitly grants this listener slot. Externally replaced listeners must revoke first. */
    fun <V : View> click(ref: ViewRef<V>, action: (V) -> Unit): AutoCloseable {
        val ownership = reserveProperties(ref, setOf("event:click"))
        var view: V? = ref.get()
        val binding = nodeOf(ref).binding.token()
        view!!.setOnClickListener {
            if (state == SessionState.ACTIVE && !closeRequested.get() && nodes[ref.identity]?.binding?.token() == binding) action(ref.get())
        }
        var closed = false
        val release = { assertOwner(); if (!closed) { closed = true; val target = view; view = null; target?.setOnClickListener(null); ownership.close() }; Unit }
        onRelease(ref, release)
        return AutoCloseable(release)
    }

    fun rebind(ref: ViewRef<*>) {
        requireActive()
        val node = nodeOf(ref)
        node.binding.advance()
        try { batchedDiscard { runCleanup(node.cleanup) } } finally { node.properties.clear(); scheduler.discardInvalid() }
    }

    fun detach() {
        assertOwner(); check(state != SessionState.DISPOSED)
        if (state == SessionState.DETACHED) return
        state = SessionState.DETACHED; attachDomain.advance(); scheduler.pause()
    }
    fun reattach() { assertOwner(); check(state == SessionState.DETACHED); attachDomain.advance(); state = SessionState.ACTIVE; scheduler.resume() }
    fun invalidateTheme() { requireActive(); themeDomain.advance() }
    fun invalidateResources() { requireActive(); check(environment.resourceAuthority == null) { "Invalidate the upstream resource authority" }; resourceDomain.advance() }

    fun release(ref: ViewRef<*>) {
        assertOwner(); check(ref.session === this)
        // A subtree release recurses through every child; discardInvalid scans the whole pending
        // queue, so only the outermost release runs it. Children are covered by that scan.
        releaseDepth++
        try {
            releaseNode(ref)
        } finally {
            releaseDepth--
        }
    }

    private fun releaseNode(ref: ViewRef<*>) {
        val node = nodes[ref.identity] ?: return
        val childIds = children.remove(node.view) ?: emptySet()
        val failures = mutableListOf<Throwable>()
        childIds.forEach { id -> nodes[id]?.let { child -> try { releaseNode(ViewRef(this, id, child.key, child.component)) } catch (t: Throwable) { failures += t } } }
        nodes.remove(ref.identity)
        node.binding.advance()
        // Closing the bindings is what invalidates their queued work, so the batch scan has to run
        // after the cleanup; doing it before forced every PropertyBinding.close to scan again.
        try { batchedDiscard { runCleanup(node.cleanup) } } catch (t: Throwable) { failures += t }
        if (releaseDepth == 1) scheduler.discardInvalid()
        views.remove(node.view)
        node.parent?.let { parent ->
            unreserve(parent, node.key)
            children[parent]?.remove(ref.identity)
            if (node.ownership != NativeOwnership.BORROWED && node.view.parent === parent) {
                try { parent.removeView(node.view) } catch (t: Throwable) { failures += t }
            }
        }
        throwCombined(failures)
    }

    /** accepted is observable immediately; completed only after owner cleanup finishes. */
    fun requestClose(): CloseRequest {
        if (closeRequested.compareAndSet(false, true)) {
            if (Looper.myLooper() === Looper.getMainLooper()) close()
            else if (!handler.post { try { close() } catch (_: Throwable) { /* failure is retained in the receipt */ } }) {
                closeReceipt.failure = IllegalStateException("Owner looper rejected close")
            }
        }
        return closeReceipt
    }

    /**
     * Submits a state update from any thread without blocking. The update is staged and merged on
     * the owner thread, where updates for the same slot posted in the meantime have already
     * collapsed into one entry — so a background producer does not need its own `Handler.post` per
     * update and does not cause extra frame requests.
     *
     * See [UiScheduler.submitStateAsync] for the rejection contract.
     */
    fun submitStateAsync(slot: Any, isValid: () -> Boolean = { true }, action: () -> Unit): SubmitResult =
        scheduler.submitStateAsync(slot, isValid, action)

    /** Non-blocking command submission from any thread; see [submitStateAsync]. */
    fun submitCommandAsync(isValid: () -> Boolean = { true }, action: () -> Unit): SubmitResult =
        scheduler.submitCommandAsync(isValid, action)

    override fun close() {
        assertOwner()
        if (state == SessionState.DISPOSED) return
        closeRequested.set(true); state = SessionState.DISPOSED; attachDomain.advance()
        val failures = mutableListOf<Throwable>()
        try { scheduler.close() } catch (t: Throwable) { failures += t }
        val identities = LongArray(nodes.size()) { nodes.keyAt(it) }
        for (index in identities.indices.reversed()) {
            val id = identities[index]
            nodes[id]?.let { node -> try { release(ViewRef(this, id, node.key, node.component)) } catch (t: Throwable) { failures += t } }
        }
        try { runCleanup(sessionCleanup) } catch (t: Throwable) { failures += t }
        nodes.clear(); parentKeys.clear(); views.clear(); children.clear(); environmentValue = null; shared.authority = null
        closeReceipt.completed = true
        if (failures.isNotEmpty()) { closeReceipt.failure = failures.first(); throwCombined(failures) }
    }

    private fun runCleanup(actions: MutableList<() -> Unit>) {
        val pending = actions.toList().asReversed(); actions.clear()
        val failures = mutableListOf<Throwable>()
        pending.forEach { try { it() } catch (t: Throwable) { failures += t } }
        throwCombined(failures)
    }
    private fun throwCombined(failures: List<Throwable>) {
        if (failures.isNotEmpty()) { val first = failures.first(); failures.drop(1).forEach(first::addSuppressed); throw first }
    }
}
