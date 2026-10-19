package io.github.kanylbullen.tung.ui.routines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.core.maskToWeekdays
import io.github.kanylbullen.tung.data.ProgramRepository
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.RoutineSummaryRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProgramDayEditUi(
    val id: String,
    val routineName: String,
    /** ISO weekdays, used in fixed mode. */
    val weekdays: Set<Int>,
)

data class ProgramDetailUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val name: String = "",
    val mode: ProgramMode = ProgramMode.ROTATING,
    val isActive: Boolean = false,
    val days: List<ProgramDayEditUi> = emptyList(),
    /** Every routine, for the "Add day" picker. */
    val routines: List<RoutineSummaryRow> = emptyList(),
)

class ProgramDetailViewModel(
    private val programId: String,
    private val programs: ProgramRepository,
    routines: RoutineRepository,
) : ViewModel() {

    val state: StateFlow<ProgramDetailUiState> = combine(
        programs.observeProgram(programId),
        programs.observeDays(programId),
        routines.observeRoutines(),
    ) { program, days, routineRows ->
        if (program == null) {
            ProgramDetailUiState(loading = false, missing = true)
        } else {
            ProgramDetailUiState(
                loading = false,
                name = program.name,
                mode = ProgramMode.fromKey(program.mode),
                isActive = program.isActive,
                days = days.map { ProgramDayEditUi(it.id, it.routineName, maskToWeekdays(it.weekdays)) },
                routines = routineRows,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgramDetailUiState())

    fun rename(name: String) {
        viewModelScope.launch { programs.rename(programId, name) }
    }

    fun activate() {
        viewModelScope.launch { programs.activate(programId) }
    }

    fun deactivate() {
        viewModelScope.launch { programs.deactivate(programId) }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            programs.delete(programId)
            onDeleted()
        }
    }

    fun addDay(routineId: String) {
        viewModelScope.launch { programs.addDay(programId, routineId) }
    }

    fun removeDay(dayId: String) {
        viewModelScope.launch { programs.removeDay(dayId) }
    }

    fun moveDay(dayId: String, delta: Int) {
        viewModelScope.launch { programs.moveDay(dayId, delta) }
    }

    fun toggleWeekday(dayId: String, weekday: Int) {
        viewModelScope.launch { programs.toggleWeekday(dayId, weekday) }
    }
}
