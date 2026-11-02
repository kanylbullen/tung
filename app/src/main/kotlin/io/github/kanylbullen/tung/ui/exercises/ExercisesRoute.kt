package io.github.kanylbullen.tung.ui.exercises

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.core.catalog.ExercisePageRef
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.displayName
import io.github.kanylbullen.tung.ui.common.factoryOf

/** The Exercises tab and the picker on the phone: [ExercisesScreen] (in `ui/`) with its ViewModel from the container. */
@Composable
fun ExercisesRoute(
    target: PickerTarget?,
    onBack: (() -> Unit)?,
    onPicked: () -> Unit,
    onOpenExercise: (String) -> Unit = {},
    onChosen: (ExerciseRef) -> Unit = {},
) {
    val container = appContainer()
    val appContext = LocalContext.current.applicationContext
    val vm: ExercisesViewModel = viewModel(
        factory = factoryOf {
            ExercisesViewModel(
                container.exerciseRepository,
                container.commands,
                container.extendedCatalog,
                target,
                container.workoutRepository,
                container.catalogueMuscles,
                now = { container.clock.millis() },
                equipmentSettings = container.equipmentSettings,
                routines = container.routineRepository,
            ) {
                it.displayName(appContext)
            }
        },
    )
    ExercisesScreen(vm, onBack, onPicked, onOpenExercise, onChosen)
}

/** The exercise page on the phone: [ExerciseDetailScreen] (in `ui/`) with its ViewModel from the container. */
@Composable
fun ExerciseDetailRoute(
    ref: ExercisePageRef,
    onBack: () -> Unit,
    onOpenWorkout: (String) -> Unit,
    onOpenProgress: (String) -> Unit = {},
) {
    val container = appContainer()
    val appContext = LocalContext.current.applicationContext
    val vm: ExerciseDetailViewModel = viewModel(
        factory = factoryOf {
            ExerciseDetailViewModel(
                ref,
                container.exerciseRepository,
                container.workoutRepository,
                container.routineRepository,
                container.catalogueDetails,
                container.commands,
            ) { it.displayName(appContext) }
        },
    )
    ExerciseDetailScreen(vm, ref, now = { container.clock.millis() }, onBack, onOpenWorkout, onOpenProgress)
}
