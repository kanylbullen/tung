package io.github.kanylbullen.tung.ui.routines

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.factoryOf

/** One program on the phone: [ProgramDetailScreen] (in `ui/`) with its ViewModel from the container. */
@Composable
fun ProgramDetailRoute(programId: String, onBack: () -> Unit) {
    val container = appContainer()
    val vm: ProgramDetailViewModel = viewModel(
        factory = factoryOf { ProgramDetailViewModel(programId, container.programRepository, container.routineRepository, container.commands) }
    )
    ProgramDetailScreen(vm, onBack)
}
