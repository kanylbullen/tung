package io.github.kanylbullen.tung.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.ui.exercises.ExercisesScreen
import io.github.kanylbullen.tung.ui.history.HistoryScreen
import io.github.kanylbullen.tung.ui.history.WorkoutDetailScreen
import io.github.kanylbullen.tung.ui.home.HomeScreen
import io.github.kanylbullen.tung.ui.summary.SummaryScreen
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.workout.ActiveWorkoutScreen

private object Routes {
    const val HOME = "home"
    const val HISTORY = "history"
    const val EXERCISES = "exercises"
    const val ARG_ID = "workoutId"
    const val WORKOUT = "workout/{$ARG_ID}"
    const val PICKER = "picker/{$ARG_ID}"
    const val DETAIL = "detail/{$ARG_ID}"
    const val SUMMARY = "summary/{$ARG_ID}"

    fun workout(id: Long) = "workout/$id"
    fun picker(id: Long) = "picker/$id"
    fun detail(id: Long) = "detail/$id"
    fun summary(id: Long) = "summary/$id"
}

private data class Tab(val route: String, @StringRes val label: Int)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.nav_home),
    Tab(Routes.HISTORY, R.string.nav_history),
    Tab(Routes.EXERCISES, R.string.nav_exercises),
)

private val idArgument = listOf(navArgument(Routes.ARG_ID) { type = NavType.LongType })

@Composable
fun TungAppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val showBar = destination?.route in tabs.map { it.route }

    Scaffold(
        containerColor = Tung.colors.background,
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = Tung.colors.raised, tonalElevation = 0.dp) {
                    tabs.forEach { tab ->
                        val selected = destination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { nav.navigateToTab(tab.route) },
                            icon = { Icon(tabIcon(tab.route), contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Tung.colors.text,
                                selectedTextColor = Tung.colors.text,
                                indicatorColor = Tung.colors.border,
                                unselectedIconColor = Tung.colors.muted,
                                unselectedTextColor = Tung.colors.muted,
                            ),
                        )
                    }
                }
            }
        },
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(inner).consumeWindowInsets(inner),
            enterTransition = { androidx.compose.animation.EnterTransition.None },
            exitTransition = { androidx.compose.animation.ExitTransition.None },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenWorkout = { id -> nav.navigate(Routes.workout(id)) },
                    onOpenDetail = { id -> nav.navigate(Routes.detail(id)) },
                    onShowHistory = { nav.navigateToTab(Routes.HISTORY) },
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(onOpenDetail = { id -> nav.navigate(Routes.detail(id)) })
            }
            composable(Routes.EXERCISES) {
                ExercisesScreen(workoutId = null, onBack = null, onPicked = {})
            }
            composable(Routes.WORKOUT, arguments = idArgument) { entry ->
                val id = entry.arguments?.getLong(Routes.ARG_ID) ?: return@composable
                ActiveWorkoutScreen(
                    workoutId = id,
                    onBack = { nav.popBackStack() },
                    onAddExercise = { nav.navigate(Routes.picker(id)) },
                    onFinished = { finishedId, saved ->
                        if (saved) {
                            nav.navigate(Routes.summary(finishedId)) { popUpTo(Routes.HOME) }
                        } else {
                            nav.popBackStack(Routes.HOME, inclusive = false)
                        }
                    },
                )
            }
            composable(Routes.PICKER, arguments = idArgument) { entry ->
                val id = entry.arguments?.getLong(Routes.ARG_ID) ?: return@composable
                ExercisesScreen(
                    workoutId = id,
                    onBack = { nav.popBackStack() },
                    onPicked = { nav.popBackStack() },
                )
            }
            composable(Routes.DETAIL, arguments = idArgument) { entry ->
                val id = entry.arguments?.getLong(Routes.ARG_ID) ?: return@composable
                WorkoutDetailScreen(workoutId = id, onBack = { nav.popBackStack() })
            }
            composable(Routes.SUMMARY, arguments = idArgument) { entry ->
                val id = entry.arguments?.getLong(Routes.ARG_ID) ?: return@composable
                SummaryScreen(workoutId = id, onDone = { nav.popBackStack(Routes.HOME, inclusive = false) })
            }
        }
    }
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun tabIcon(route: String): Painter = when (route) {
    Routes.HISTORY -> painterResource(R.drawable.ic_history)
    Routes.EXERCISES -> painterResource(R.drawable.ic_dumbbell)
    else -> rememberVectorPainter(Icons.Filled.Home)
}
