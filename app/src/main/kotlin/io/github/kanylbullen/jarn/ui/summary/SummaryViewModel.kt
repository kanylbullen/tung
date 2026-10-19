package io.github.kanylbullen.jarn.ui.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.jarn.data.WorkoutRepository
import io.github.kanylbullen.jarn.ui.common.WorkoutReport
import io.github.kanylbullen.jarn.ui.common.buildWorkoutReport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

data class SummaryUiState(
    val loading: Boolean = true,
    val report: WorkoutReport? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SummaryViewModel(workoutId: Long, repo: WorkoutRepository) : ViewModel() {
    val state: StateFlow<SummaryUiState> =
        combine(repo.observeWorkout(workoutId), repo.observeExercises(workoutId)) { w, e -> w to e }
            .mapLatest { (workout, exercises) ->
                SummaryUiState(
                    loading = false,
                    report = workout?.let { buildWorkoutReport(it, exercises, repo, withRecords = true) },
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SummaryUiState())
}
