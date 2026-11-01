package io.github.kanylbullen.tung.ui

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
import io.github.kanylbullen.tung.core.catalog.ExercisePageRef
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.factoryOf
import io.github.kanylbullen.tung.ui.importing.ImportScreen
import io.github.kanylbullen.tung.ui.importing.ImportViewModel
import io.github.kanylbullen.tung.ui.importing.readImportFile
import io.github.kanylbullen.tung.ui.ai.AssistantScreen
import io.github.kanylbullen.tung.ui.exercises.ExerciseDetailScreen
import io.github.kanylbullen.tung.ui.exercises.ExercisesScreen
import io.github.kanylbullen.tung.ui.exercises.PickerTarget
import io.github.kanylbullen.tung.ui.history.HistoryScreen
import io.github.kanylbullen.tung.ui.history.WorkoutDetailScreen
import io.github.kanylbullen.tung.ui.home.HomeScreen
import io.github.kanylbullen.tung.ui.progress.ExerciseProgressScreen
import io.github.kanylbullen.tung.ui.progress.ProgressScreen
import io.github.kanylbullen.tung.ui.routines.ProgramDetailRoute
import io.github.kanylbullen.tung.ui.routines.ProgramsRoute
import io.github.kanylbullen.tung.ui.routines.RoutineDetailRoute
import io.github.kanylbullen.tung.ui.routines.RoutinesRoute
import io.github.kanylbullen.tung.ui.settings.SETTINGS_ROUTE
import io.github.kanylbullen.tung.ui.settings.SettingsGroup
import io.github.kanylbullen.tung.ui.settings.settingsGraph
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
    const val ROUTINES = "routines"
    const val ARG_ROUTINE = "routineId"
    const val ROUTINE = "routine/{$ARG_ROUTINE}"
    const val ROUTINE_PICKER = "routine/{$ARG_ROUTINE}/add"
    const val PROGRAMS = "programs"
    const val ARG_PROGRAM = "programId"
    const val PROGRAM = "program/{$ARG_PROGRAM}"
    const val SETTINGS = SETTINGS_ROUTE
    const val ARG_ASK = "ask"
    const val ASSISTANT = "assistant?$ARG_ASK={$ARG_ASK}"
    const val ARG_REF = "ref"
    const val EXERCISE = "exercise/{$ARG_REF}"
    const val ARG_URI = "uri"
    const val IMPORT = "import?$ARG_URI={$ARG_URI}"
    const val IMPORT_PICK = "import/pick"

    /** Where the import's picker leaves its answer, in the import entry's saved state: an `ExercisePageRef.route`. */
    const val IMPORT_PICKED = "import_picked"

    const val PROGRESS = "progress"
    const val ARG_EXERCISE = "exerciseId"
    const val EXERCISE_PROGRESS = "progress/{$ARG_EXERCISE}"

    fun workout(id: String) = "workout/$id"
    fun picker(id: String) = "picker/$id"
    fun detail(id: String) = "detail/$id"
    fun summary(id: String) = "summary/$id"
    fun routine(id: String) = "routine/$id"
    fun routinePicker(id: String) = "routine/$id/add"
    fun program(id: String) = "program/$id"
    fun exerciseProgress(exerciseId: String) = "progress/$exerciseId"

    /** The import's preview of the file at [uri] (a document the system picker granted). */
    fun import(uri: android.net.Uri) = "import?$ARG_URI=${Uri.encode(uri.toString())}"

    /** An exercise page; [ref] is `ExercisePageRef.route` (a Room id or `ext:<id>`), encoded since extended ids are free text. */
    fun exercise(ref: String) = "exercise/${Uri.encode(ref)}"

    /** The Assistant screen; with [ask], words the voice grammar did not understand, asked as free text at once. */
    fun assistant(ask: String? = null) = if (ask == null) "assistant" else "assistant?$ARG_ASK=${Uri.encode(ask)}"
}

private data class Tab(val route: String, @StringRes val label: Int)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.nav_home),
    Tab(Routes.ROUTINES, R.string.nav_routines),
    Tab(Routes.EXERCISES, R.string.nav_exercises),
    Tab(Routes.HISTORY, R.string.nav_history),
    Tab(Routes.PROGRESS, R.string.nav_progress),
)

private val idArgument = listOf(navArgument(Routes.ARG_ID) { type = NavType.StringType })

/** A workout to open from a notification; [askToFinish] for the ongoing notification's "Finish". */
data class OpenWorkout(val workoutId: String, val askToFinish: Boolean = false)

/**
 * @param openWorkout a workout to open on top of Home, set when a "Rest over"
 *   or the ongoing notification is tapped; [onWorkoutOpened] is called once it has been.
 */
@Composable
fun TungAppRoot(openWorkout: OpenWorkout? = null, onWorkoutOpened: () -> Unit = {}) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    // The workout whose finish dialog the screen should open; one-shot, cleared by the screen.
    var finishRequest by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(openWorkout) {
        if (openWorkout != null) {
            if (openWorkout.askToFinish) finishRequest = openWorkout.workoutId
            nav.navigate(Routes.workout(openWorkout.workoutId)) {
                popUpTo(Routes.HOME)
                launchSingleTop = true
            }
            onWorkoutOpened()
        }
    }
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
                    onShowRoutines = { nav.navigateToTab(Routes.ROUTINES) },
                    onOpenRoutine = { id -> nav.navigate(Routes.routine(id)) },
                    onShowSettings = { nav.navigate(Routes.SETTINGS) },
                    onShowAssistant = { nav.navigate(Routes.assistant()) },
                )
            }
            composable(
                Routes.ASSISTANT,
                arguments = listOf(navArgument(Routes.ARG_ASK) { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                AssistantScreen(
                    ask = entry.arguments?.getString(Routes.ARG_ASK),
                    onBack = { nav.popBackStack() },
                    onOpenSettings = { nav.navigate(SettingsGroup.ASSISTANT.route) },
                    onProgramSaved = { nav.navigateToTab(Routes.ROUTINES) },
                    onOpenWorkout = { id -> nav.navigate(Routes.workout(id)) { popUpTo(Routes.HOME) } },
                )
            }
            settingsGraph(nav, onImportFile = { uri -> nav.navigate(Routes.import(uri)) })
            composable(
                Routes.IMPORT,
                arguments = listOf(navArgument(Routes.ARG_URI) { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                val uri = entry.arguments?.getString(Routes.ARG_URI)?.let(Uri::parse) ?: return@composable
                val container = appContainer()
                val appContext = LocalContext.current.applicationContext
                val vm: ImportViewModel = viewModel(
                    factory = factoryOf { ImportViewModel(container.imports, container.ai, container.aiSettings) { readImportFile(appContext, uri) } },
                )
                val picked by entry.savedStateHandle.getStateFlow<String?>(Routes.IMPORT_PICKED, null).collectAsStateWithLifecycle()
                LaunchedEffect(picked) {
                    val route = picked ?: return@LaunchedEffect
                    ExercisePageRef.parse(route)?.let { vm.onPicked(it.toExerciseRef()) }
                    entry.savedStateHandle[Routes.IMPORT_PICKED] = null
                }
                ImportScreen(
                    vm = vm,
                    onBack = { nav.popBackStack() },
                    onPick = { nav.navigate(Routes.IMPORT_PICK) },
                    onDone = { nav.popBackStack() },
                )
            }
            composable(Routes.IMPORT_PICK) {
                ExercisesScreen(
                    target = PickerTarget.Choose,
                    onBack = { nav.popBackStack() },
                    onPicked = {},
                    onChosen = { ref ->
                        val route = when (ref) {
                            is ExerciseRef.ById -> ExercisePageRef.Library(ref.id).route
                            is ExerciseRef.Extended -> ExercisePageRef.Extended(ref.fedId).route
                            else -> null
                        }
                        nav.previousBackStackEntry?.savedStateHandle?.set(Routes.IMPORT_PICKED, route)
                        nav.popBackStack()
                    },
                )
            }
            composable(Routes.ROUTINES) {
                RoutinesRoute(
                    onOpenRoutine = { id -> nav.navigate(Routes.routine(id)) },
                    onOpenWorkout = { id -> nav.navigate(Routes.workout(id)) { popUpTo(Routes.HOME) } },
                    onShowPrograms = { nav.navigate(Routes.PROGRAMS) },
                )
            }
            composable(Routes.PROGRAMS) {
                ProgramsRoute(
                    onBack = { nav.popBackStack() },
                    onOpenProgram = { id -> nav.navigate(Routes.program(id)) },
                )
            }
            composable(
                Routes.PROGRAM,
                arguments = listOf(navArgument(Routes.ARG_PROGRAM) { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString(Routes.ARG_PROGRAM) ?: return@composable
                ProgramDetailRoute(programId = id, onBack = { nav.popBackStack() })
            }
            composable(
                Routes.ROUTINE,
                arguments = listOf(navArgument(Routes.ARG_ROUTINE) { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString(Routes.ARG_ROUTINE) ?: return@composable
                RoutineDetailRoute(
                    routineId = id,
                    onBack = { nav.popBackStack() },
                    onAddExercise = { nav.navigate(Routes.routinePicker(id)) },
                    onStart = { workoutId -> nav.navigate(Routes.workout(workoutId)) { popUpTo(Routes.HOME) } },
                )
            }
            composable(
                Routes.ROUTINE_PICKER,
                arguments = listOf(navArgument(Routes.ARG_ROUTINE) { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString(Routes.ARG_ROUTINE) ?: return@composable
                ExercisesScreen(
                    target = PickerTarget.Routine(id),
                    onBack = { nav.popBackStack() },
                    onPicked = { nav.popBackStack() },
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(onOpenDetail = { id -> nav.navigate(Routes.detail(id)) })
            }
            composable(Routes.EXERCISES) {
                ExercisesScreen(
                    target = null,
                    onBack = null,
                    onPicked = {},
                    onOpenExercise = { ref -> nav.navigate(Routes.exercise(ref)) },
                )
            }
            composable(
                Routes.EXERCISE,
                arguments = listOf(navArgument(Routes.ARG_REF) { type = NavType.StringType }),
            ) { entry ->
                val ref = entry.arguments?.getString(Routes.ARG_REF)?.let(ExercisePageRef::parse) ?: return@composable
                ExerciseDetailScreen(
                    ref = ref,
                    onBack = { nav.popBackStack() },
                    onOpenWorkout = { id -> nav.navigate(Routes.workout(id)) { popUpTo(Routes.HOME) } },
                    onOpenProgress = { id -> nav.navigate(Routes.exerciseProgress(id)) },
                )
            }
            composable(Routes.PROGRESS) {
                ProgressScreen(onOpenExercise = { id -> nav.navigate(Routes.exerciseProgress(id)) })
            }
            composable(
                Routes.EXERCISE_PROGRESS,
                arguments = listOf(navArgument(Routes.ARG_EXERCISE) { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString(Routes.ARG_EXERCISE) ?: return@composable
                ExerciseProgressScreen(exerciseId = id, onBack = { nav.popBackStack() })
            }
            composable(Routes.WORKOUT, arguments = idArgument) { entry ->
                val id = entry.arguments?.getString(Routes.ARG_ID) ?: return@composable
                ActiveWorkoutScreen(
                    workoutId = id,
                    askToFinish = finishRequest == id,
                    onFinishAsked = { finishRequest = null },
                    onBack = { nav.popBackStack() },
                    onAddExercise = { nav.navigate(Routes.picker(id)) },
                    onAskAssistant = { text -> nav.navigate(Routes.assistant(text)) },
                    onFinished = { finishedId, saved ->
                        if (saved) {
                            nav.navigate(Routes.summary(finishedId)) { popUpTo(Routes.HOME) }
                        } else {
                            nav.popBackStack(Routes.HOME, inclusive = false)
                        }
                    },
                    onDiscarded = { nav.popBackStack(Routes.HOME, inclusive = false) },
                )
            }
            composable(Routes.PICKER, arguments = idArgument) { entry ->
                val id = entry.arguments?.getString(Routes.ARG_ID) ?: return@composable
                ExercisesScreen(
                    target = PickerTarget.Workout(id),
                    onBack = { nav.popBackStack() },
                    onPicked = { nav.popBackStack() },
                )
            }
            composable(Routes.DETAIL, arguments = idArgument) { entry ->
                val id = entry.arguments?.getString(Routes.ARG_ID) ?: return@composable
                WorkoutDetailScreen(workoutId = id, onBack = { nav.popBackStack() })
            }
            composable(Routes.SUMMARY, arguments = idArgument) { entry ->
                val id = entry.arguments?.getString(Routes.ARG_ID) ?: return@composable
                SummaryScreen(
                    workoutId = id,
                    onDone = { nav.popBackStack(Routes.HOME, inclusive = false) },
                    onOpenProgress = { exerciseId -> nav.navigate(Routes.exerciseProgress(exerciseId)) },
                )
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
    Routes.ROUTINES -> rememberVectorPainter(Icons.AutoMirrored.Filled.List)
    Routes.PROGRESS -> painterResource(R.drawable.ic_trending_up)
    else -> rememberVectorPainter(Icons.Filled.Home)
}
