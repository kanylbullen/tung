package io.github.kanylbullen.tung.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val recent: List<WorkoutSummaryRow> = emptyList(),
    val hasMore: Boolean = false,
)

class HomeViewModel(private val workouts: WorkoutRepository) : ViewModel() {

    val state: StateFlow<HomeUiState> = combine(
        workouts.observeActiveWorkout(),
        workouts.observeFinishedWorkouts(),
    ) { active, finished ->
        HomeUiState(
            loading = false,
            activeWorkout = active,
            recent = finished.take(RECENT_COUNT),
            hasMore = finished.size > RECENT_COUNT,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Starts a workout, or resumes the one in progress, and hands back its id. */
    fun startOrResume(onReady: (Long) -> Unit) {
        viewModelScope.launch { onReady(workouts.startOrResumeWorkout()) }
    }

    private companion object {
        const val RECENT_COUNT = 5
    }
}
