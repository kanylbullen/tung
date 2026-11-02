package io.github.kanylbullen.tung.ui.ai

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.factoryOf

/** The `ui` module's [AssistantScreen] with its ViewModel from the phone's container. */
@Composable
fun AssistantRoute(
    ask: String?,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onProgramSaved: () -> Unit,
    onOpenWorkout: (String) -> Unit,
) {
    val container = appContainer()
    val vm: AssistantViewModel = viewModel(factory = factoryOf { AssistantViewModel(container.ai, container.aiSettings) })
    AssistantScreen(vm, ask = ask, onBack = onBack, onOpenSettings = onOpenSettings, onProgramSaved = onProgramSaved, onOpenWorkout = onOpenWorkout)
}
