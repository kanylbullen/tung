package io.github.kanylbullen.tung.ui.routines

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.factoryOf

/** One routine on the phone: [RoutineDetailScreen] (in `ui/`) with its ViewModel from the container. */
@Composable
fun RoutineDetailRoute(
    routineId: String,
    onBack: () -> Unit,
    onAddExercise: () -> Unit,
    onStart: (workoutId: String) -> Unit = {},
) {
    val container = appContainer()
    val vm: RoutineDetailViewModel = viewModel(
        factory = factoryOf {
            RoutineDetailViewModel(routineId, container.routineRepository, container.workoutRepository, container.commands, container.catalogueMuscles)
        }
    )
    RoutineDetailScreen(vm, onBack, onAddExercise, onStart)
}
