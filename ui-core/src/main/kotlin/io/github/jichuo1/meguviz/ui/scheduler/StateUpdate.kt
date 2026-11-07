package io.github.jichuo1.meguviz.ui.scheduler

/**
 * One slot update inside a [UiScheduler.submitStates] batch.
 *
 * The last update for a slot wins, exactly like calling [UiScheduler.submitState] repeatedly.
 * [isValid] is rechecked immediately before [action] runs, never at submission time.
 */
class StateUpdate(
    val slot: Any,
    val isValid: () -> Boolean = { true },
    val action: () -> Unit,
)
