package io.github.kanylbullen.tung.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandHandler
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.CommandResult
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

/** Why "Change date and time" was refused, for the line in its dialog. */
enum class TimeChangeProblem { FUTURE, TOO_LONG, OTHER }

/** What a refusal of `set_workout_time` says, as a [TimeChangeProblem]. */
fun timeChangeProblem(reasons: List<String>): TimeChangeProblem = when {
    reasons.any { it.endsWith("is in the future") } -> TimeChangeProblem.FUTURE
    reasons.any { it.startsWith("a workout lasts at most") } -> TimeChangeProblem.TOO_LONG
    else -> TimeChangeProblem.OTHER
}

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

    /**
     * Moves the workout to [startedAt], lasting [durationMillis]
     * (`set_workout_time`); [onResult] gets null when it moved, else what was wrong.
     */
    fun setTime(startedAt: Long, durationMillis: Long, onResult: (TimeChangeProblem?) -> Unit) {
        viewModelScope.launch {
            val result = commands.execute(Command.SetWorkoutTime(workoutId, startedAt, startedAt + durationMillis))
            onResult((result as? CommandResult.Rejected)?.let { timeChangeProblem(it.reasons) } ?: if (result is CommandResult.Done) null else TimeChangeProblem.OTHER)
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            commands.execute(Command.DeleteWorkout(workoutId))
            onDeleted()
        }
    }
}
