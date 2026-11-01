package io.github.kanylbullen.tung.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandHandler
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.id
import io.github.kanylbullen.tung.data.PastWorkoutSettings
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.data.WorkoutSummaryRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HistoryUiState(
    val loading: Boolean = true,
    val workouts: List<WorkoutSummaryRow> = emptyList(),
    /** The workout in progress, if any: a past workout cannot be logged beside it. */
    val activeWorkoutId: String? = null,
)

/**
 * History, and "Log past workout": [now] is the clock the dialog offers its
 * start from (the container's, so a flow test can set it).
 */
class HistoryViewModel(
    workouts: WorkoutRepository,
    private val commands: CommandHandler,
    val now: () -> Long,
    /** Where this device remembers the workouts it starts here, so they count as past from the start. */
    private val pastWorkouts: PastWorkoutSettings? = null,
) : ViewModel() {
    val state: StateFlow<HistoryUiState> =
        combine(workouts.observeFinishedWorkouts(), workouts.observeActiveWorkout()) { finished, active ->
            HistoryUiState(loading = false, workouts = finished, activeWorkoutId = active?.id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    /**
     * Starts a workout at [startedAt] (`start_workout` with `started_at`) and
     * remembers it as logged after the fact; [onStarted] gets its id.
     */
    fun logPastWorkout(startedAt: Long, onStarted: (String) -> Unit) {
        viewModelScope.launch {
            val id = commands.execute(Command.StartWorkout(startedAt = startedAt)).id(CommandIds.WORKOUT_ID) ?: return@launch
            pastWorkouts?.add(id)
            onStarted(id)
        }
    }
}
