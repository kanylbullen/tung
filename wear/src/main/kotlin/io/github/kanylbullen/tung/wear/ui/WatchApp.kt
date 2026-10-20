package io.github.kanylbullen.tung.wear.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import io.github.kanylbullen.tung.wear.AdjustField
import io.github.kanylbullen.tung.wear.WatchViewModel
import kotlin.math.roundToInt

private const val MAIN = "main"
private const val ADJUST = "adjust/{field}/{setId}"

private fun adjustRoute(field: AdjustField, setId: String) = "adjust/${field.name}/$setId"

@Composable
fun WatchApp(viewModel: WatchViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val navController = rememberSwipeDismissableNavController()

    SwipeDismissableNavHost(navController = navController, startDestination = MAIN) {
        composable(MAIN) {
            val state = ui.state
            when {
                !ui.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(indicatorColor = TungColors.accent)
                }
                state == null || !state.isActive -> StartScreen(
                    routines = state?.routines.orEmpty(),
                    neverHeardFromPhone = state == null,
                    phoneUnreachable = ui.phoneUnreachable,
                    onStartWorkout = viewModel::startWorkout,
                    onStartRoutine = viewModel::startRoutine,
                )
                else -> WorkoutScreen(
                    state = state,
                    phoneUnreachable = ui.phoneUnreachable,
                    onLogSet = viewModel::logSet,
                    onSelectSet = viewModel::selectSet,
                    onAdjust = { field, setId -> navController.navigate(adjustRoute(field, setId)) },
                    onFinish = viewModel::finishWorkout,
                    onSkipRest = viewModel::skipRest,
                    onAddRest = viewModel::addRest,
                    claimRestBuzz = { endsAt ->
                        (viewModel.lastBuzzedRestEnd != endsAt).also { viewModel.lastBuzzedRestEnd = endsAt }
                    },
                )
            }
        }
        composable(ADJUST) { entry ->
            val field = entry.arguments?.getString("field")?.let { name -> AdjustField.entries.firstOrNull { it.name == name } }
            val setId = entry.arguments?.getString("setId")
            val set = setId?.let { ui.state?.set(it) }
            if (field == null || set == null) {
                // The set is gone (the workout was finished or the set deleted on the phone).
                LaunchedEffect(Unit) { navController.popBackStack() }
                return@composable
            }
            AdjustScreen(
                field = field,
                initial = if (field == AdjustField.Weight) set.weightKg else set.reps.toDouble(),
                onDone = { value ->
                    when (field) {
                        AdjustField.Weight -> if (value != set.weightKg) viewModel.setWeight(set.setId, value)
                        AdjustField.Reps -> if (value.roundToInt() != set.reps) viewModel.setReps(set.setId, value.roundToInt())
                    }
                    navController.popBackStack()
                },
            )
        }
    }
}
