package io.github.manuelrtsch.sidetrack

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The single mutable owner of [SidetrackUiState].
 *
 * Feature collaborators take this store instead of the ViewModel. That is what lets a piece of
 * the screen be pulled into its own class without a back-reference to the ViewModel that hosts
 * it: the store, not the hosting class, is the contract.
 *
 * Every write is a whole-state transform, so the flow stays the one source of truth and the
 * derived slices in `UiSlices.kt` keep working unchanged.
 */
internal class UiStateStore(initial: SidetrackUiState) {

    private val _state = MutableStateFlow(initial)

    val state: StateFlow<SidetrackUiState> = _state.asStateFlow()

    val value: SidetrackUiState get() = _state.value

    fun update(transform: (SidetrackUiState) -> SidetrackUiState) = _state.update(transform)
}
