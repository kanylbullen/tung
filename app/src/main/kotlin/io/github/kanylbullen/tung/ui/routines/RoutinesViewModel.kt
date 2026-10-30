package io.github.kanylbullen.tung.ui.routines

import androidx.lifecycle.SavedStateHandle
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.github.kanylbullen.tung.core.muscles.MuscleBreakdown
import io.github.kanylbullen.tung.data.CatalogueMuscles
import io.github.kanylbullen.tung.data.routineMuscles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

data class RoutinesUiState(
    val loading: Boolean = true,
    val routines: List<RoutineSummaryRow> = emptyList(),
    /** False while a workout is in progress: there is at most one, and Home offers to resume it. */
    val canStart: Boolean = false,
    /** The active program's name, shown on the Programs entry; null when none is active. */
    val activeProgram: String? = null,
    /** The routines chosen in selection mode, in list order; selection mode is on while it is not empty. */
    val selected: List<String> = emptyList(),
    /** The muscles each routine works, by routine id ([routineMuscles]); a routine with none is absent. */
    val muscles: Map<String, MuscleBreakdown> = emptyMap(),
) {
    val selecting: Boolean get() = selected.isNotEmpty()
}

/** What the delete dialog asks about: how many routines, and how many program days go with them. */
data class DeleteRoutinesConfirm(val ids: List<String>, val programDays: Int)

class RoutinesViewModel(
    private val routines: RoutineRepository,
    workouts: WorkoutRepository,
    programs: ProgramRepository,
    private val commands: CommandHandler,
    catalogueMuscles: CatalogueMuscles,
    /** Holds the selection, so it survives rotation and the process being killed. */
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    private val selection = savedState.getStateFlow(SELECTED, ArrayList<String>())

    /** The muscles every routine works, recomputed off the main thread when a routine's exercises change. */
    private val muscles: Flow<Map<String, MuscleBreakdown>> = combine(
        routines.observeRoutineExerciseLinks(),
        flow { emit(catalogueMuscles.index()) },
    ) { links, index -> routineMuscles(links, index) }.flowOn(Dispatchers.Default)

    val state: StateFlow<RoutinesUiState> = combine(
        routines.observeRoutines(),
        workouts.observeActiveWorkout(),
        programs.observeActiveProgram(),
        selection,
        muscles,
    ) { list, active, program, selected, muscles ->
        // A routine deleted elsewhere drops out of the selection.
        val chosen = selected.toSet()
        RoutinesUiState(
            loading = false,
            routines = list,
            canStart = active == null,
            activeProgram = program?.name,
            selected = list.map { it.id }.filter { it in chosen },
            muscles = muscles,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoutinesUiState())

    private val _confirm = MutableStateFlow<DeleteRoutinesConfirm?>(null)

    /** Non-null while the delete dialog is open. */
    val confirm: StateFlow<DeleteRoutinesConfirm?> = _confirm.asStateFlow()

    /** Creates an empty routine called [name]; [onCreated] gets its id, to open it. */
    fun create(name: String, onCreated: (String) -> Unit) {
        viewModelScope.launch { commands.execute(Command.CreateRoutine(name)).id(CommandIds.ROUTINE_ID)?.let(onCreated) }
    }

    fun start(routineId: String, onReady: (String) -> Unit) {
        viewModelScope.launch { commands.execute(Command.StartRoutine(routineId)).id(CommandIds.WORKOUT_ID)?.let(onReady) }
    }

    /** Long-press: selection mode starts with this routine, or it is toggled when already selecting. */
    fun toggle(routineId: String) {
        val current = selection.value
        savedState[SELECTED] = ArrayList(if (routineId in current) current - routineId else current + routineId)
    }

    fun selectAll() {
        savedState[SELECTED] = ArrayList(state.value.routines.map { it.id })
    }

    fun clearSelection() {
        savedState[SELECTED] = ArrayList<String>()
    }

    /** Opens the delete dialog for the selection, with how many program days use those routines. */
    fun askDelete() {
        val ids = state.value.selected
        if (ids.isEmpty()) return
        viewModelScope.launch { _confirm.value = DeleteRoutinesConfirm(ids, routines.programDaysUsing(ids)) }
    }

    fun dismissDelete() {
        _confirm.value = null
    }

    /** Deletes what the dialog named, in one command, and leaves selection mode. */
    fun confirmDelete() {
        val confirm = _confirm.value ?: return
        _confirm.value = null
        clearSelection()
        viewModelScope.launch { commands.execute(Command.DeleteRoutines(confirm.ids)) }
    }

    private companion object {
        const val SELECTED = "selectedRoutines"
    }
}
