package io.github.kanylbullen.tung.ui.home

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.factoryOf
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toKotlinTimeZone
import java.time.LocalDate

/**
 * Home on the phone: [HomeScreen] (in `ui/`) with its ViewModel from the
 * container. Today and the zone come from the container's clock (settable in
 * the flow tests), and a discard clears the rest timer's alarm through
 * `RestTimerController`.
 */
@Composable
fun HomeRoute(
    onOpenWorkout: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onShowHistory: () -> Unit,
    onShowRoutines: () -> Unit,
    onOpenRoutine: (String) -> Unit,
    onShowSettings: () -> Unit,
    onShowAssistant: () -> Unit,
) {
    val container = appContainer()
    val vm: HomeViewModel = viewModel(
        factory = factoryOf {
            HomeViewModel(
                container.workoutRepository,
                container.routineRepository,
                container.programRepository,
                container.commands,
                currentDate = { LocalDate.now(container.clock).toKotlinLocalDate() },
                currentZone = { container.clock.zone.toKotlinTimeZone() },
                clearRestTimer = { workoutId -> container.restTimer.set(workoutId, null) },
                catalogueMuscles = container.catalogueMuscles,
            )
        }
    )
    HomeScreen(vm, onOpenWorkout, onOpenDetail, onShowHistory, onShowRoutines, onOpenRoutine, onShowSettings, onShowAssistant)
}
