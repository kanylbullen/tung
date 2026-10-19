package io.github.kanylbullen.tung.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.ui.common.WorkoutReport
import io.github.kanylbullen.tung.ui.common.buildWorkoutReport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WorkoutDetailUiState(
    val loading: Boolean = true,
    val report: WorkoutReport? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutDetailViewModel(
    private val workoutId: String,
    private val repo: WorkoutRepository,
) : ViewModel() {

    val state: StateFlow<WorkoutDetailUiState> =
        combine(repo.observeWorkout(workoutId), repo.observeExercises(workoutId)) { w, e -> w to e }
            .mapLatest { (workout, exercises) ->
                WorkoutDetailUiState(
                    loading = false,
                    report = workout?.let { buildWorkoutReport(it, exercises, repo, withRecords = false) },
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutDetailUiState())

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repo.deleteWorkout(workoutId)
            onDeleted()
        }
    }
}
