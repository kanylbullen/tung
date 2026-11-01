package io.github.kanylbullen.tung.ui.progress

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.factoryOf
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toKotlinTimeZone
import java.time.LocalDate

// The Progress screens on the phone: the `ui/` screens with their ViewModels from the
// container. Today and the zone come from the container's clock, which the flow tests set.

/** The Progress tab. */
@Composable
fun ProgressRoute(onOpenExercise: (String) -> Unit) {
    val container = appContainer()
    val vm: ProgressViewModel = viewModel(
        factory = factoryOf {
            ProgressViewModel(
                container.workoutRepository,
                container.exerciseRepository,
                container.progressSettings,
                currentDate = { LocalDate.now(container.clock).toKotlinLocalDate() },
                currentZone = { container.clock.zone.toKotlinTimeZone() },
            )
        },
    )
    ProgressScreen(vm, onOpenExercise)
}

/** One exercise's progress (route `progress/{exerciseId}`). */
@Composable
fun ExerciseProgressRoute(exerciseId: String, onBack: () -> Unit) {
    ExerciseProgressScreen(exerciseProgressViewModel(exerciseId), onBack)
}

/** The progress sheet inside a workout, for the workout screen's progress slot. */
@Composable
fun ProgressSheetRoute(exerciseId: String, exerciseName: String, proposed: LoggedSet?, onDismiss: () -> Unit) {
    ProgressSheet(exerciseProgressViewModel(exerciseId, shared = true), exerciseName, proposed, onDismiss)
}

/**
 * The exercise progress screen's view model, keyed `progress-<exerciseId>`;
 * with [shared] (the sheet in a workout, keyed `progress-sheet-<exerciseId>`)
 * its metric and span are the Progress tab's, remembered in [ProgressSettings][io.github.kanylbullen.tung.data.ProgressSettings].
 */
@Composable
private fun exerciseProgressViewModel(exerciseId: String, shared: Boolean = false): ExerciseProgressViewModel {
    val container = appContainer()
    return viewModel(
        key = if (shared) "progress-sheet-$exerciseId" else "progress-$exerciseId",
        factory = factoryOf {
            ExerciseProgressViewModel(
                exerciseId,
                container.workoutRepository,
                container.exerciseRepository,
                currentDate = { LocalDate.now(container.clock).toKotlinLocalDate() },
                currentZone = { container.clock.zone.toKotlinTimeZone() },
                settings = if (shared) container.progressSettings else null,
            )
        },
    )
}
