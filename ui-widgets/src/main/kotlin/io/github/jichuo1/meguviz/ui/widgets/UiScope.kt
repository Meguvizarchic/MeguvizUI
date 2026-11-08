package io.github.jichuo1.meguviz.ui.widgets

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.widget.FrameLayout
import io.github.jichuo1.meguviz.ui.CreationEnvironment
import io.github.jichuo1.meguviz.ui.UiSession
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * One [UiSession], its root [FrameLayout] and the [FrameScope] bound to that root.
 *
 * Obtain it with [openMeguvizUi] instead of assembling the three by hand. [close] disposes the
 * session; it is idempotent, so a lifecycle callback and an explicit `onDestroy` call are both safe.
 *
 * ```kotlin
 * val ui = openMeguvizUi()
 * setContentView(ui.root)
 * val title = ui.scope.text(ViewKey("title"), frameParams()) { text = "Ready" }
 * title.bind(Properties.plainText()).submit("Updated")
 * ```
 */
class UiScope(
    val session: UiSession,
    val root: FrameLayout,
) : AutoCloseable {
    /** Scope over [root]; the same instance for the lifetime of this object. */
    val scope: FrameScope = FrameScope(session, root)

    private var closed = false

    /** True after [close]; the session is already disposed then and the scope is unusable. */
    val isClosed: Boolean get() = closed

    override fun close() {
        if (closed) return
        closed = true
        session.close()
    }
}

/**
 * Opened scopes by Activity. Values are weak because a scope holds its Activity through the session;
 * a strong value would keep a destroyed Activity reachable for as long as the map lives. Only the
 * main thread touches this map, matching [UiSession]'s owner-thread contract.
 */
private val opened = WeakHashMap<Activity, WeakReference<UiScope>>()

/**
 * One-line entry: creates the root [FrameLayout], a [UiSession] for this Activity and a [FrameScope]
 * over that root, and closes the session when the Activity is destroyed. No `onDestroy` needed.
 *
 * - **Idempotent**: calling this again on the same Activity returns the same open scope, so calling it
 *   from `onResume`-like repeated paths does not stack sessions.
 * - **Misuse throws**: a non-main thread or an already destroyed Activity raises instead of returning
 *   a session nobody closes.
 * - **No AndroidX**: this is the framework `Activity` extension, so a plain `android.app.Activity`
 *   works and no `LifecycleOwner` is required.
 *
 * The root is caller-owned: pass it to `setContentView` yourself and keep building through
 * [UiScope.scope]. For a `WindowSession`-backed session use the Android bridge's
 * `AndroidUiBinding.open(window, root)` instead.
 */
// No @JvmOverloads: there are no default parameters, so it generated no overloads and only
// misled readers into expecting a Java-visible variant. The single JVM signature is
// UiScopeKt.openMeguvizUi(Activity), which Java callers use directly.
fun Activity.openMeguvizUi(): UiScope {
    check(Looper.myLooper() === Looper.getMainLooper()) { "openMeguvizUi must be called on the main thread" }
    check(!isDestroyed) { "${javaClass.name} is destroyed" }
    opened[this]?.get()?.let { existing ->
        if (!existing.isClosed) return existing
        // The caller closed it; fall through and open a fresh one rather than handing back a corpse.
    }
    val scope = UiScope(UiSession(CreationEnvironment(this)), FrameLayout(this))
    opened[this] = WeakReference(scope)
    // The callback deliberately touches neither `opened` nor the Activity's session: reaching the
    // private top-level map from another class would make the compiler emit a public static
    // `access$getOpened$p` into the JVM API baseline (the same reason SharedFrameDriver passes a
    // lambda instead of a bound method reference). A stale entry is harmless: the next call sees a
    // closed scope and replaces it.
    val application = application
    application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        override fun onActivityDestroyed(activity: Activity) {
            if (activity !== this@openMeguvizUi) return
            application.unregisterActivityLifecycleCallbacks(this)
            scope.close()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    })
    return scope
}
