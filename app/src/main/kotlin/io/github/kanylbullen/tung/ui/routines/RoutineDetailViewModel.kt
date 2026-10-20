package io.github.kanylbullen.tung.ui.routines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandHandler
import io.github.kanylbullen.tung.data.RoutineRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RoutineExerciseUi(
    val id: String,
    /** Fallback name; shown through `exerciseDisplayName(nameKey, name)`. */
    val name: String,
    val nameKey: String?,
    val targetSets: Int,
    val targetReps: Int?,
)

data class RoutineDetailUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val name: String = "",
    val exercises: List<RoutineExerciseUi> = emptyList(),
)

class RoutineDetailViewModel(
    private val routineId: String,
    repo: RoutineRepository,
    private val commands: CommandHandler,
) : ViewModel() {

    val state: StateFlow<RoutineDetailUiState> = combine(
        repo.observeRoutine(routineId),
        repo.observeRoutineExercises(routineId),
    ) { routine, exercises ->
        if (routine == null) {
            RoutineDetailUiState(loading = false, missing = true)
        } else {
            RoutineDetailUiState(
                loading = false,
                name = routine.name,
                exercises = exercises.map { e ->
                    RoutineExerciseUi(
                        id = e.routineExercise.id,
                        name = e.exercise.name,
                        nameKey = e.exercise.nameKey,
                        targetSets = e.routineExercise.targetSets,
                        targetReps = e.routineExercise.targetReps,
                    )
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoutineDetailUiState())

    fun rename(name: String) {
        viewModelScope.launch { commands.execute(Command.RenameRoutine(routineId, name)) }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            commands.execute(Command.DeleteRoutine(routineId))
            onDeleted()
        }
    }

    fun move(routineExerciseId: String, delta: Int) {
        viewModelScope.launch { commands.execute(Command.MoveRoutineExercise(routineExerciseId, delta)) }
    }

    fun changeSets(routineExerciseId: String, delta: Int) {
        viewModelScope.launch { commands.execute(Command.AdjustTargetSets(routineExerciseId, delta)) }
    }

    /** [reps] 1..999, or null to clear the target. */
    fun setTargetReps(routineExerciseId: String, reps: Int?) {
        viewModelScope.launch { commands.execute(Command.SetTargetReps(routineExerciseId, reps)) }
    }

    fun remove(routineExerciseId: String) {
        viewModelScope.launch { commands.execute(Command.RemoveRoutineExercise(routineExerciseId)) }
    }
}
