package io.github.kanylbullen.tung.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.RoutineSummaryRow
import io.github.kanylbullen.tung.data.Workout
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.data.WorkoutSummaryRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val activeWorkout: Workout? = null,
    /** Most recently used first; empty means the section is not shown at all. */
    val routines: List<RoutineSummaryRow> = emptyList(),
    val recent: List<WorkoutSummaryRow> = emptyList(),
    val hasMore: Boolean = false,
)

class HomeViewModel(
    private val workouts: WorkoutRepository,
    private val routines: RoutineRepository,
) : ViewModel() {

    val state: StateFlow<HomeUiState> = combine(
        workouts.observeActiveWorkout(),
        workouts.observeFinishedWorkouts(),
        routines.observeRoutines(),
    ) { active, finished, routineRows ->
        HomeUiState(
            loading = false,
            activeWorkout = active,
            routines = routineRows,
            recent = finished.take(RECENT_COUNT),
            hasMore = finished.size > RECENT_COUNT,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Starts a workout, or resumes the one in progress, and hands back its id. */
    fun startOrResume(onReady: (String) -> Unit) {
        viewModelScope.launch { onReady(workouts.startOrResumeWorkout()) }
    }

    /** Starts a workout from a routine and hands back its id. */
    fun startRoutine(routineId: String, onReady: (String) -> Unit) {
        viewModelScope.launch { routines.start(routineId)?.let(onReady) }
    }

    private companion object {
        const val RECENT_COUNT = 5
    }
}
