package io.github.kanylbullen.tung.ui.summary

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.factoryOf
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toKotlinTimeZone
import java.time.LocalDate

/** The summary after Finish on the phone: [SummaryScreen] (in `ui/`) with its ViewModel from the container, and a toast when it is saved as a routine. */
@Composable
fun SummaryRoute(workoutId: String, onDone: () -> Unit, onOpenProgress: (String) -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val vm: SummaryViewModel = viewModel(
        factory = factoryOf {
            SummaryViewModel(
                workoutId,
                container.workoutRepository,
                container.commands,
                container.catalogueMuscles,
                container.progressSettings,
                currentDate = { LocalDate.now(container.clock).toKotlinLocalDate() },
                currentZone = { container.clock.zone.toKotlinTimeZone() },
            )
        },
    )
    SummaryScreen(vm, onDone, onOpenProgress) { name ->
        Toast.makeText(context, context.getString(R.string.routine_saved, name), Toast.LENGTH_SHORT).show()
    }
}
