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
import androidx.navigation.NavHostController
import io.github.kanylbullen.tung.wear.AccountUi
import io.github.kanylbullen.tung.wear.AccountViewModel
import io.github.kanylbullen.tung.wear.AdjustField
import io.github.kanylbullen.tung.wear.WatchMode
import io.github.kanylbullen.tung.wear.WatchViewModel
import kotlin.math.roundToInt

private const val MAIN = "main"
private const val ADJUST = "adjust/{field}/{setId}"
private const val SETTINGS = "settings"
private const val SERVERS = "servers"
private const val LINK = "link"

private fun adjustRoute(field: AdjustField, setId: String) = "adjust/${field.name}/$setId"

@Composable
fun WatchApp(viewModel: WatchViewModel, accountViewModel: AccountViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val account by accountViewModel.ui.collectAsStateWithLifecycle()
    val link by accountViewModel.link.collectAsStateWithLifecycle()
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
                    standalone = ui.mode == WatchMode.STANDALONE,
                    neverHeardFromPhone = state == null,
                    phoneUnreachable = ui.phoneUnreachable,
                    onStartWorkout = viewModel::startWorkout,
                    onStartRoutine = viewModel::startRoutine,
                    onSettings = { navController.navigate(SETTINGS) },
                )
                else -> WorkoutScreen(
                    state = state,
                    phoneUnreachable = ui.phoneUnreachable,
                    onLogSet = viewModel::logSet,
                    onAddSet = viewModel::addSet,
                    onSelectSet = viewModel::selectSet,
                    onAdjust = { field, setId -> navController.navigate(adjustRoute(field, setId)) },
                    onFinish = viewModel::finishWorkout,
                    onSkipRest = viewModel::skipRest,
                    onAddRest = viewModel::addRest,
                    claimRestBuzz = viewModel::claimRestBuzz,
                )
            }
        }
        composable(SETTINGS) {
            SettingsScreen(
                ui = account,
                onSyncNow = accountViewModel::syncNow,
                onSignOut = accountViewModel::signOut,
                onLinkTo = { server ->
                    accountViewModel.beginLink(server)
                    navController.navigate(LINK)
                },
                onChooseServer = { navController.navigate(SERVERS) },
            )
        }
        composable(SERVERS) {
            ServerRoute(navController, account, accountViewModel)
        }
        composable(LINK) {
            if (link.linked) {
                // Signed in: the watch is standalone now; back to its own start screen.
                LaunchedEffect(Unit) { navController.popBackStack(MAIN, inclusive = false) }
            }
            LinkCodeScreen(
                link = link,
                onDigit = accountViewModel::type,
                onDelete = accountViewModel::delete,
                onSubmit = accountViewModel::submit,
            )
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

/** The server list; picking or typing one goes on to the code. */
@Composable
private fun ServerRoute(navController: NavHostController, account: AccountUi, accountViewModel: AccountViewModel) {
    ServerScreen(
        ui = account,
        onPick = { server ->
            accountViewModel.beginLink(server)
            navController.navigate(LINK)
        },
        onTyped = { text ->
            accountViewModel.beginLinkTyped(text).also { error -> if (error == null) navController.navigate(LINK) }
        },
    )
}
