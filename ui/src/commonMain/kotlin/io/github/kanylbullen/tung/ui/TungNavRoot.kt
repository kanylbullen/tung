package io.github.kanylbullen.tung.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.catalog.ExercisePageRef
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.data.TungServices
import io.github.kanylbullen.tung.ui.ai.AssistantScreen
import io.github.kanylbullen.tung.ui.ai.AssistantViewModel
import io.github.kanylbullen.tung.ui.exercises.ExerciseDetailScreen
import io.github.kanylbullen.tung.ui.exercises.ExerciseDetailViewModel
import io.github.kanylbullen.tung.ui.exercises.ExercisesScreen
import io.github.kanylbullen.tung.ui.exercises.ExercisesViewModel
import io.github.kanylbullen.tung.ui.exercises.PickerTarget
import io.github.kanylbullen.tung.ui.history.HistoryScreen
import io.github.kanylbullen.tung.ui.history.HistoryViewModel
import io.github.kanylbullen.tung.ui.history.WorkoutDetailScreen
import io.github.kanylbullen.tung.ui.history.WorkoutDetailViewModel
import io.github.kanylbullen.tung.ui.home.HomeScreen
import io.github.kanylbullen.tung.ui.home.HomeViewModel
import io.github.kanylbullen.tung.ui.importing.ImportScreen
import io.github.kanylbullen.tung.ui.importing.ImportViewModel
import io.github.kanylbullen.tung.ui.progress.ExerciseProgressScreen
import io.github.kanylbullen.tung.ui.progress.ExerciseProgressViewModel
import io.github.kanylbullen.tung.ui.progress.ProgressScreen
import io.github.kanylbullen.tung.ui.progress.ProgressSheet
import io.github.kanylbullen.tung.ui.progress.ProgressViewModel
import io.github.kanylbullen.tung.ui.resources.*
import io.github.kanylbullen.tung.ui.routines.ProgramDetailScreen
import io.github.kanylbullen.tung.ui.routines.ProgramDetailViewModel
import io.github.kanylbullen.tung.ui.routines.ProgramsScreen
import io.github.kanylbullen.tung.ui.routines.ProgramsViewModel
import io.github.kanylbullen.tung.ui.routines.RoutineDetailScreen
import io.github.kanylbullen.tung.ui.routines.RoutineDetailViewModel
import io.github.kanylbullen.tung.ui.routines.RoutinesScreen
import io.github.kanylbullen.tung.ui.routines.RoutinesViewModel
import io.github.kanylbullen.tung.ui.settings.AboutScreen
import io.github.kanylbullen.tung.ui.settings.AccountScreen
import io.github.kanylbullen.tung.ui.settings.AccountViewModel
import io.github.kanylbullen.tung.ui.settings.AiSettingsViewModel
import io.github.kanylbullen.tung.ui.settings.AssistantSettingsScreen
import io.github.kanylbullen.tung.ui.settings.DataSettingsScreen
import io.github.kanylbullen.tung.ui.settings.DevicesScreen
import io.github.kanylbullen.tung.ui.settings.DevicesViewModel
import io.github.kanylbullen.tung.ui.settings.EquipmentSectionViewModel
import io.github.kanylbullen.tung.ui.settings.FeedbackScreen
import io.github.kanylbullen.tung.ui.settings.FeedbackViewModel
import io.github.kanylbullen.tung.ui.settings.ImportSectionViewModel
import io.github.kanylbullen.tung.ui.settings.SettingsGroup
import io.github.kanylbullen.tung.ui.settings.SettingsScreen
import io.github.kanylbullen.tung.ui.settings.SettingsViewModel
import io.github.kanylbullen.tung.ui.settings.TrainingScreen
import io.github.kanylbullen.tung.ui.settings.TrainingSettingsViewModel
import io.github.kanylbullen.tung.ui.summary.SummaryScreen
import io.github.kanylbullen.tung.ui.summary.SummaryViewModel
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.workout.ActiveWorkoutScreen
import io.github.kanylbullen.tung.ui.workout.ActiveWorkoutViewModel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

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
    const val SETTINGS = "settings"
    const val ARG_ASK = "ask"
    const val ASSISTANT = "assistant?$ARG_ASK={$ARG_ASK}"
    const val ARG_REF = "ref"
    const val EXERCISE = "exercise/{$ARG_REF}"
    const val ARG_SOURCE = "source"
    const val IMPORT = "import?$ARG_SOURCE={$ARG_SOURCE}"
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

    /** The import's preview of the picked [source] (`AppPlatform.rememberImportFilePicker`). */
    fun import(source: String) = "import?$ARG_SOURCE=${encodeArgument(source)}"

    /** An exercise page; [ref] is `ExercisePageRef.route` (a Room id or `ext:<id>`), encoded since extended ids are free text. */
    fun exercise(ref: String) = "exercise/${encodeArgument(ref)}"

    /** The Assistant screen; with [ask], words the voice grammar did not understand, asked as free text at once. */
    fun assistant(ask: String? = null) = if (ask == null) "assistant" else "assistant?$ARG_ASK=${encodeArgument(ask)}"
}

/**
 * [value] percent-encoded as UTF-8, everything but RFC 3986's unreserved
 * characters, so a route argument survives `/`, `?`, `&` and spaces;
 * navigation decodes it before the destination reads it.
 */
internal fun encodeArgument(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val c = byte.toInt() and 0xFF
        if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
            c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
        ) {
            append(c.toChar())
        } else {
            append('%')
            append("0123456789ABCDEF"[c shr 4])
            append("0123456789ABCDEF"[c and 0xF])
        }
    }
}

private data class Tab(val route: String, val label: StringResource)

private val tabs = listOf(
    Tab(Routes.HOME, Res.string.nav_home),
    Tab(Routes.ROUTINES, Res.string.nav_routines),
    Tab(Routes.EXERCISES, Res.string.nav_exercises),
    Tab(Routes.HISTORY, Res.string.nav_history),
    Tab(Routes.PROGRESS, Res.string.nav_progress),
)

private val idArgument = listOf(navArgument(Routes.ARG_ID) { type = NavType.StringType })

/** A workout to open from outside the app (a notification); [askToFinish] for the ongoing notification's "Finish". */
data class OpenWorkout(val workoutId: String, val askToFinish: Boolean = false)

/**
 * The whole app on every platform: the bottom bar's five tabs (Home,
 * Routines, Exercises, History, Progress) and every screen pushed over them,
 * each with its ViewModel built from [services] and what only the platform
 * knows from [platform].
 *
 * @param openWorkout a workout to open on top of Home, set when a "Rest over"
 *   or the ongoing notification is tapped; [onWorkoutOpened] is called once it has been.
 */
@Composable
fun TungNavRoot(
    services: TungServices,
    platform: AppPlatform,
    openWorkout: OpenWorkout? = null,
    onWorkoutOpened: () -> Unit = {},
) {
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
    val scope = rememberCoroutineScope()
    val routineSaved: (String) -> Unit = { name ->
        scope.launch { platform.showMessage(getString(Res.string.routine_saved, name)) }
    }

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
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
        ) {
            composable(Routes.HOME) {
                val vm = viewModel {
                    HomeViewModel(
                        services.workoutRepository,
                        services.routineRepository,
                        services.programRepository,
                        services.commands,
                        currentDate = services::today,
                        currentZone = services::zone,
                        clearRestTimer = { workoutId -> services.restTimer.set(workoutId, null) },
                        catalogueMuscles = services.catalogueMuscles,
                    )
                }
                HomeScreen(
                    vm,
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
                val vm = viewModel { AssistantViewModel(services.ai, services.aiSettings) }
                AssistantScreen(
                    vm,
                    ask = entry.argument(Routes.ARG_ASK),
                    onBack = { nav.popBackStack() },
                    onOpenSettings = { nav.navigate(SettingsGroup.ASSISTANT.route) },
                    onProgramSaved = { nav.navigateToTab(Routes.ROUTINES) },
                    onOpenWorkout = { id -> nav.navigate(Routes.workout(id)) { popUpTo(Routes.HOME) } },
                )
            }
            settingsGraph(nav, services, platform, onImportFile = { source -> nav.navigate(Routes.import(source)) })
            composable(
                Routes.IMPORT,
                arguments = listOf(navArgument(Routes.ARG_SOURCE) { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                val source = entry.argument(Routes.ARG_SOURCE) ?: return@composable
                val vm = viewModel {
                    ImportViewModel(services.imports, services.ai, services.aiSettings) { platform.readImportFile(source) }
                }
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
                ExercisesRoute(
                    services, platform,
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
                val vm = viewModel {
                    RoutinesViewModel(
                        services.routineRepository,
                        services.workoutRepository,
                        services.programRepository,
                        services.commands,
                        services.catalogueMuscles,
                        createSavedStateHandle(),
                    )
                }
                RoutinesScreen(
                    vm,
                    onOpenRoutine = { id -> nav.navigate(Routes.routine(id)) },
                    onOpenWorkout = { id -> nav.navigate(Routes.workout(id)) { popUpTo(Routes.HOME) } },
                    onShowPrograms = { nav.navigate(Routes.PROGRAMS) },
                ) { enabled, onBack -> platform.BackHandler(enabled, onBack) }
            }
            composable(Routes.PROGRAMS) {
                val vm = viewModel { ProgramsViewModel(services.programRepository, services.commands) }
                ProgramsScreen(vm, onBack = { nav.popBackStack() }, onOpenProgram = { id -> nav.navigate(Routes.program(id)) })
            }
            composable(
                Routes.PROGRAM,
                arguments = listOf(navArgument(Routes.ARG_PROGRAM) { type = NavType.StringType }),
            ) { entry ->
                val id = entry.argument(Routes.ARG_PROGRAM) ?: return@composable
                val vm = viewModel {
                    ProgramDetailViewModel(id, services.programRepository, services.routineRepository, services.commands)
                }
                ProgramDetailScreen(vm, onBack = { nav.popBackStack() })
            }
            composable(
                Routes.ROUTINE,
                arguments = listOf(navArgument(Routes.ARG_ROUTINE) { type = NavType.StringType }),
            ) { entry ->
                val id = entry.argument(Routes.ARG_ROUTINE) ?: return@composable
                val vm = viewModel {
                    RoutineDetailViewModel(
                        id,
                        services.routineRepository,
                        services.workoutRepository,
                        services.commands,
                        services.catalogueMuscles,
                    )
                }
                RoutineDetailScreen(
                    vm,
                    onBack = { nav.popBackStack() },
                    onAddExercise = { nav.navigate(Routes.routinePicker(id)) },
                    onStart = { workoutId -> nav.navigate(Routes.workout(workoutId)) { popUpTo(Routes.HOME) } },
                )
            }
            composable(
                Routes.ROUTINE_PICKER,
                arguments = listOf(navArgument(Routes.ARG_ROUTINE) { type = NavType.StringType }),
            ) { entry ->
                val id = entry.argument(Routes.ARG_ROUTINE) ?: return@composable
                ExercisesRoute(
                    services, platform,
                    target = PickerTarget.Routine(id),
                    onBack = { nav.popBackStack() },
                    onPicked = { nav.popBackStack() },
                )
            }
            composable(Routes.HISTORY) {
                val vm = viewModel {
                    HistoryViewModel(services.workoutRepository, services.commands, services::nowMillis, services.pastWorkouts)
                }
                HistoryScreen(
                    vm,
                    onOpenDetail = { id -> nav.navigate(Routes.detail(id)) },
                    onOpenWorkout = { id -> nav.navigate(Routes.workout(id)) { popUpTo(Routes.HOME) } },
                )
            }
            composable(Routes.EXERCISES) {
                ExercisesRoute(
                    services, platform,
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
                val ref = entry.argument(Routes.ARG_REF)?.let(ExercisePageRef::parse) ?: return@composable
                val vm = viewModel {
                    ExerciseDetailViewModel(
                        ref,
                        services.exerciseRepository,
                        services.workoutRepository,
                        services.routineRepository,
                        services.catalogueDetails,
                        services.commands,
                        platform::exerciseName,
                    )
                }
                ExerciseDetailScreen(
                    vm,
                    ref,
                    now = services::nowMillis,
                    onBack = { nav.popBackStack() },
                    onOpenWorkout = { id -> nav.navigate(Routes.workout(id)) { popUpTo(Routes.HOME) } },
                    onOpenProgress = { id -> nav.navigate(Routes.exerciseProgress(id)) },
                )
            }
            composable(Routes.PROGRESS) {
                val vm = viewModel {
                    ProgressViewModel(
                        services.workoutRepository,
                        services.exerciseRepository,
                        services.progressSettings,
                        currentDate = services::today,
                        currentZone = services::zone,
                    )
                }
                ProgressScreen(vm, onOpenExercise = { id -> nav.navigate(Routes.exerciseProgress(id)) })
            }
            composable(
                Routes.EXERCISE_PROGRESS,
                arguments = listOf(navArgument(Routes.ARG_EXERCISE) { type = NavType.StringType }),
            ) { entry ->
                val id = entry.argument(Routes.ARG_EXERCISE) ?: return@composable
                ExerciseProgressScreen(exerciseProgressViewModel(services, id), onBack = { nav.popBackStack() })
            }
            composable(Routes.WORKOUT, arguments = idArgument) { entry ->
                val id = entry.argument(Routes.ARG_ID) ?: return@composable
                val vm = viewModel {
                    ActiveWorkoutViewModel(
                        workoutId = id,
                        repo = services.workoutRepository,
                        commands = services.commands,
                        restTimer = services.restTimer,
                        savedState = createSavedStateHandle(),
                        pastWorkouts = services.pastWorkouts,
                    )
                }
                val assistantConfigured by remember { services.aiSettings.config.map { it.configured } }
                    .collectAsStateWithLifecycle(false)
                ActiveWorkoutScreen(
                    vm = vm,
                    platform = platform.rememberWorkoutPlatform(),
                    assistantConfigured = assistantConfigured,
                    progressSheet = { exerciseId, exerciseName, proposed, onDismiss ->
                        ProgressSheetRoute(services, exerciseId, exerciseName, proposed, onDismiss)
                    },
                    onBack = { nav.popBackStack() },
                    askToFinish = finishRequest == id,
                    onFinishAsked = { finishRequest = null },
                    onAddExercise = { nav.navigate(Routes.picker(id)) },
                    onFinished = { finishedId, saved ->
                        if (saved) {
                            nav.navigate(Routes.summary(finishedId)) { popUpTo(Routes.HOME) }
                        } else {
                            nav.popBackStack(Routes.HOME, inclusive = false)
                        }
                    },
                    onDiscarded = { nav.popBackStack(Routes.HOME, inclusive = false) },
                    onAskAssistant = { text -> nav.navigate(Routes.assistant(text)) },
                )
            }
            composable(Routes.PICKER, arguments = idArgument) { entry ->
                val id = entry.argument(Routes.ARG_ID) ?: return@composable
                ExercisesRoute(
                    services, platform,
                    target = PickerTarget.Workout(id),
                    onBack = { nav.popBackStack() },
                    onPicked = { nav.popBackStack() },
                )
            }
            composable(Routes.DETAIL, arguments = idArgument) { entry ->
                val id = entry.argument(Routes.ARG_ID) ?: return@composable
                val vm = viewModel { WorkoutDetailViewModel(id, services.workoutRepository, services.commands) }
                WorkoutDetailScreen(vm, onBack = { nav.popBackStack() }, onRoutineSaved = routineSaved)
            }
            composable(Routes.SUMMARY, arguments = idArgument) { entry ->
                val id = entry.argument(Routes.ARG_ID) ?: return@composable
                val vm = viewModel {
                    SummaryViewModel(
                        id,
                        services.workoutRepository,
                        services.commands,
                        services.catalogueMuscles,
                        services.progressSettings,
                        currentDate = services::today,
                        currentZone = services::zone,
                    )
                }
                SummaryScreen(
                    vm,
                    onDone = { nav.popBackStack(Routes.HOME, inclusive = false) },
                    onOpenProgress = { exerciseId -> nav.navigate(Routes.exerciseProgress(exerciseId)) },
                    onRoutineSaved = routineSaved,
                )
            }
        }
    }
}

/**
 * Settings' destinations: the list at [Routes.SETTINGS] and one screen per
 * [SettingsGroup]. Back from a group returns to the list. The health group
 * exists only where [AppPlatform.healthSettings] does.
 */
private fun NavGraphBuilder.settingsGraph(
    nav: NavController,
    services: TungServices,
    platform: AppPlatform,
    onImportFile: (source: String) -> Unit,
) {
    val back: () -> Unit = { nav.popBackStack() }
    val openAccount: () -> Unit = { nav.navigate(SettingsGroup.ACCOUNT.route) { popUpTo(Routes.SETTINGS) } }
    composable(Routes.SETTINGS) {
        val vm = viewModel {
            SettingsViewModel(services.syncAccount, services.push, services.restTimer, services.aiSettings, services.imports)
        }
        SettingsScreen(
            vm,
            version = platform.versionName,
            buildType = platform.buildType,
            healthSummary = platform.healthSummary(),
            onBack = back,
            onOpenGroup = { group ->
                if (group != SettingsGroup.HEALTH_CONNECT || platform.healthSettings != null) nav.navigate(group.route)
            },
        )
    }
    composable(SettingsGroup.ACCOUNT.route) {
        val vm = viewModel { AccountViewModel(services.syncAccount, platform.defaultServerUrl) }
        AccountScreen(
            vm,
            onBack = back,
            googleIdToken = platform.rememberGoogleIdToken(),
            appleIdToken = platform.rememberAppleIdToken(),
        )
    }
    composable(SettingsGroup.DEVICES.route) {
        val vm = viewModel { DevicesViewModel(services.syncAccount, services.push) }
        DevicesScreen(vm, onBack = back, onOpenAccount = openAccount)
    }
    composable(SettingsGroup.TRAINING.route) {
        val vm = viewModel { TrainingSettingsViewModel(services.restTimer) }
        val equipment = viewModel { EquipmentSectionViewModel(services.equipmentSettings) }
        TrainingScreen(vm, equipment, onBack = back)
    }
    composable(SettingsGroup.ASSISTANT.route) {
        val vm = viewModel { AiSettingsViewModel(services.aiSettings, services.ai) }
        AssistantSettingsScreen(vm, onBack = back)
    }
    platform.healthSettings?.let { health ->
        composable(SettingsGroup.HEALTH_CONNECT.route) { health(back) }
    }
    composable(SettingsGroup.DATA.route) {
        val vm = viewModel { ImportSectionViewModel(services.imports, services.routineRepository, services.commands) }
        val pickFile = platform.rememberImportFilePicker(onImportFile)
        DataSettingsScreen(vm, onBack = back, onPickFile = pickFile)
    }
    composable(SettingsGroup.FEEDBACK.route) {
        val vm = viewModel { FeedbackViewModel(services.syncAccount, platform.feedbackPlatform, platform.feedbackVersion) }
        val pick = platform.rememberScreenshotPicker { vm.addScreenshots(it) }
        FeedbackScreen(vm, onBack = back, onOpenAccount = openAccount, onPickScreenshots = pick)
    }
    composable(SettingsGroup.ABOUT.route) {
        AboutScreen(platform.versionName, platform.buildType, onBack = back)
    }
}

/** The Exercises tab, the pickers (a workout's, a routine's, the import's chooser) and their ViewModel. */
@Composable
private fun ExercisesRoute(
    services: TungServices,
    platform: AppPlatform,
    target: PickerTarget?,
    onBack: (() -> Unit)?,
    onPicked: () -> Unit,
    onOpenExercise: (String) -> Unit = {},
    onChosen: (ExerciseRef) -> Unit = {},
) {
    val vm = viewModel {
        ExercisesViewModel(
            services.exerciseRepository,
            services.commands,
            services.extendedCatalog,
            target,
            services.workoutRepository,
            services.catalogueMuscles,
            now = services::nowMillis,
            equipmentSettings = services.equipmentSettings,
            routines = services.routineRepository,
            displayName = platform::exerciseName,
        )
    }
    ExercisesScreen(vm, onBack, onPicked, onOpenExercise, onChosen)
}

/** The progress sheet inside a workout, for the workout screen's progress slot. */
@Composable
private fun ProgressSheetRoute(
    services: TungServices,
    exerciseId: String,
    exerciseName: String,
    proposed: LoggedSet?,
    onDismiss: () -> Unit,
) {
    ProgressSheet(exerciseProgressViewModel(services, exerciseId, shared = true), exerciseName, proposed, onDismiss)
}

/**
 * The exercise progress screen's view model, keyed `progress-<exerciseId>`;
 * with [shared] (the sheet in a workout, keyed `progress-sheet-<exerciseId>`)
 * its metric and span are the Progress tab's, remembered in
 * [ProgressSettings][io.github.kanylbullen.tung.data.ProgressSettings].
 */
@Composable
private fun exerciseProgressViewModel(services: TungServices, exerciseId: String, shared: Boolean = false): ExerciseProgressViewModel =
    viewModel(key = if (shared) "progress-sheet-$exerciseId" else "progress-$exerciseId") {
        ExerciseProgressViewModel(
            exerciseId,
            services.workoutRepository,
            services.exerciseRepository,
            currentDate = services::today,
            currentZone = services::zone,
            settings = if (shared) services.progressSettings else null,
        )
    }

/** A route argument, as navigation decoded it into the entry's saved state. */
private fun NavBackStackEntry.argument(name: String): String? = savedStateHandle.get<String>(name)

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun tabIcon(route: String): Painter = when (route) {
    Routes.HISTORY -> painterResource(Res.drawable.ic_history)
    Routes.EXERCISES -> painterResource(Res.drawable.ic_dumbbell)
    Routes.ROUTINES -> rememberVectorPainter(Icons.AutoMirrored.Filled.List)
    Routes.PROGRESS -> painterResource(Res.drawable.ic_trending_up)
    else -> rememberVectorPainter(Icons.Filled.Home)
}
