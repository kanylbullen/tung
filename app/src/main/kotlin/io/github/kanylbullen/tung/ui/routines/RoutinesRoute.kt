package io.github.kanylbullen.tung.ui.routines

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.kanylbullen.tung.ui.common.appContainer

/**
 * The Routines tab on the phone: [RoutinesScreen] (in `ui/`) with its
 * ViewModel from the container, and back leaving selection mode before it
 * leaves the tab.
 */
@Composable
fun RoutinesRoute(
    onOpenRoutine: (String) -> Unit,
    onOpenWorkout: (String) -> Unit,
    onShowPrograms: () -> Unit,
) {
    val container = appContainer()
    val vm: RoutinesViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                RoutinesViewModel(
                    container.routineRepository,
                    container.workoutRepository,
                    container.programRepository,
                    container.commands,
                    container.catalogueMuscles,
                    createSavedStateHandle(),
                )
            }
        }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.selecting) { vm.clearSelection() }
    RoutinesScreen(vm, onOpenRoutine, onOpenWorkout, onShowPrograms)
}
