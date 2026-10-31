package io.github.kanylbullen.tung.ui.routines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandHandler
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.id
import io.github.kanylbullen.tung.data.ProgramRepository
import io.github.kanylbullen.tung.data.ProgramSummaryRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProgramsUiState(
    val loading: Boolean = true,
    /** The active program first, then by name. */
    val programs: List<ProgramSummaryRow> = emptyList(),
)

class ProgramsViewModel(programs: ProgramRepository, private val commands: CommandHandler) : ViewModel() {

    val state: StateFlow<ProgramsUiState> = programs.observePrograms()
        .map { ProgramsUiState(loading = false, programs = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgramsUiState())

    /** Creates a program and hands back its id. */
    fun create(name: String, mode: ProgramMode, onCreated: (String) -> Unit) {
        viewModelScope.launch { commands.execute(Command.CreateProgram(name, mode)).id(CommandIds.PROGRAM_ID)?.let(onCreated) }
    }

    fun activate(programId: String) {
        viewModelScope.launch { commands.execute(Command.ActivateProgram(programId)) }
    }
}
