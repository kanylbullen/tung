package io.github.kanylbullen.tung.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandHandler
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.id
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
    private val commands: CommandHandler,
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

    /** Saves this workout as a new routine; [onSaved] gets false if there was nothing to save. */
    fun saveAsRoutine(name: String, onSaved: (Boolean) -> Unit) {
        viewModelScope.launch { onSaved(commands.execute(Command.SaveRoutine(workoutId, name)).id(CommandIds.ROUTINE_ID) != null) }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            commands.execute(Command.DeleteWorkout(workoutId))
            onDeleted()
        }
    }
}
