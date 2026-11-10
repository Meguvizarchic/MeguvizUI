package io.github.jichuo1.meguviz.ui.android

import io.github.jichuo1.meguviz.android.runtime.resources.ResourceScope
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceState
import io.github.jichuo1.meguviz.ui.ResourceAuthority

/** Tokens and validity remain owned by the actual upstream resource domain. */
fun androidResourceAuthority(scope: ResourceScope): ResourceAuthority = androidResourceAuthority(scope.state())

/**
 * While neither the resources nor the theme of [state] have been invalidated, `capture` returns
 * the same token object: a token that is still current is indistinguishable from a new one, and
 * returning it again lets the session recognise "nothing changed" by identity and reuse its own
 * binding token. Any invalidation yields a new object. A closed state or a call from another
 * thread fails exactly as `state.token(...)` does.
 */
fun androidResourceAuthority(state: ResourceState): ResourceAuthority {
    var latest: ResourceState.Token? = null
    return ResourceAuthority(
        capture = {
            val reusable = latest
            if (reusable != null && state.isCurrent(reusable)) reusable
            else state.token(ResourceState.ALL).also { latest = it }
        },
        isCurrent = { token -> token is ResourceState.Token && state.isCurrent(token) },
    )
}
