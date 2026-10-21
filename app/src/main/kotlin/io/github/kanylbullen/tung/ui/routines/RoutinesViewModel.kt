package io.github.kanylbullen.tung.ui.routines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandHandler
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.id
import io.github.kanylbullen.tung.data.ProgramRepository
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
    /** The active program's name, shown on the Programs entry; null when none is active. */
    val activeProgram: String? = null,
)

class RoutinesViewModel(
    routines: RoutineRepository,
    workouts: WorkoutRepository,
    programs: ProgramRepository,
    private val commands: CommandHandler,
) : ViewModel() {

    val state: StateFlow<RoutinesUiState> = combine(
        routines.observeRoutines(),
        workouts.observeActiveWorkout(),
        programs.observeActiveProgram(),
    ) { list, active, program ->
        RoutinesUiState(loading = false, routines = list, canStart = active == null, activeProgram = program?.name)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoutinesUiState())

    /** Creates an empty routine called [name]; [onCreated] gets its id, to open it. */
    fun create(name: String, onCreated: (String) -> Unit) {
        viewModelScope.launch { commands.execute(Command.CreateRoutine(name)).id(CommandIds.ROUTINE_ID)?.let(onCreated) }
    }

    fun start(routineId: String, onReady: (String) -> Unit) {
        viewModelScope.launch { commands.execute(Command.StartRoutine(routineId)).id(CommandIds.WORKOUT_ID)?.let(onReady) }
    }
}
