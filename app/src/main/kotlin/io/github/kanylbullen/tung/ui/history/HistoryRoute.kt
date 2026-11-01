package io.github.kanylbullen.tung.ui.history

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.factoryOf

/**
 * History on the phone: [HistoryScreen] (in `ui/`) with its ViewModel from the container; "Log past
 * workout" offers its start from the container's clock and opens the workout it starts ([onOpenWorkout]).
 */
@Composable
fun HistoryRoute(onOpenDetail: (String) -> Unit, onOpenWorkout: (String) -> Unit) {
    val container = appContainer()
    val vm: HistoryViewModel = viewModel(
        factory = factoryOf { HistoryViewModel(container.workoutRepository, container.commands) { container.clock.millis() } }
    )
    HistoryScreen(vm, onOpenDetail, onOpenWorkout)
}

/** One finished workout on the phone: [WorkoutDetailScreen] (in `ui/`), and a toast when it is saved as a routine. */
@Composable
fun WorkoutDetailRoute(workoutId: String, onBack: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val vm: WorkoutDetailViewModel = viewModel(
        factory = factoryOf {
            WorkoutDetailViewModel(workoutId, container.workoutRepository, container.commands)
        }
    )
    WorkoutDetailScreen(vm, onBack) { name ->
        Toast.makeText(context, context.getString(R.string.routine_saved, name), Toast.LENGTH_SHORT).show()
    }
}
