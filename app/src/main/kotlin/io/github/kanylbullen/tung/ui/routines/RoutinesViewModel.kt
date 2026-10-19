package io.github.kanylbullen.tung.ui.routines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.RoutineSummaryRow
import io.github.kanylbullen.tung.data.WorkoutRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RoutinesUiState(
    val loading: Boolean = true,
    val routines: List<RoutineSummaryRow> = emptyList(),
    /** False while a workout is in progress: there is at most one, and Home offers to resume it. */
    val canStart: Boolean = false,
)

class RoutinesViewModel(
    private val routines: RoutineRepository,
    workouts: WorkoutRepository,
) : ViewModel() {

    val state: StateFlow<RoutinesUiState> = combine(
        routines.observeRoutines(),
        workouts.observeActiveWorkout(),
    ) { list, active ->
        RoutinesUiState(loading = false, routines = list, canStart = active == null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoutinesUiState())

    fun start(routineId: String, onReady: (String) -> Unit) {
        viewModelScope.launch { routines.start(routineId)?.let(onReady) }
    }
}
