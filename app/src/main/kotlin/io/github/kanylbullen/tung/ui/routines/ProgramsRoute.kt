package io.github.kanylbullen.tung.ui.routines

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.factoryOf

/** Every program on the phone: [ProgramsScreen] (in `ui/`) with its ViewModel from the container. */
@Composable
fun ProgramsRoute(onBack: () -> Unit, onOpenProgram: (String) -> Unit) {
    val container = appContainer()
    val vm: ProgramsViewModel = viewModel(factory = factoryOf { ProgramsViewModel(container.programRepository, container.commands) })
    ProgramsScreen(vm, onBack, onOpenProgram)
}
