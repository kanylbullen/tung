package io.github.kanylbullen.jarn.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.jarn.data.WorkoutRepository
import io.github.kanylbullen.jarn.data.WorkoutSummaryRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class HistoryUiState(
    val loading: Boolean = true,
    val workouts: List<WorkoutSummaryRow> = emptyList(),
)

class HistoryViewModel(workouts: WorkoutRepository) : ViewModel() {
    val state: StateFlow<HistoryUiState> = workouts.observeFinishedWorkouts()
        .map { HistoryUiState(loading = false, workouts = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())
}
