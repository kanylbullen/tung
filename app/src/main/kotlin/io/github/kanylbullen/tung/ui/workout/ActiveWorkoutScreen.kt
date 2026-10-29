package io.github.kanylbullen.tung.ui.workout

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.PluralsRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.ui.progress.ProgressSheet
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.painterResource
import io.github.kanylbullen.tung.TungApp
import io.github.kanylbullen.tung.core.REPS_RULER
import io.github.kanylbullen.tung.core.RestTimer
import io.github.kanylbullen.tung.core.WEIGHT_RULER
import io.github.kanylbullen.tung.core.isLogged
import io.github.kanylbullen.tung.core.parseReps
import io.github.kanylbullen.tung.core.parseWeight
import io.github.kanylbullen.tung.core.stepReps
import io.github.kanylbullen.tung.core.stepWeight
import io.github.kanylbullen.tung.core.voice.VoiceLanguage
import io.github.kanylbullen.tung.core.voice.parseUtterance
import io.github.kanylbullen.tung.ui.common.PrimaryButton
import io.github.kanylbullen.tung.ui.common.SecondaryButton
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.clockText
import io.github.kanylbullen.tung.ui.common.exerciseDisplayName
import io.github.kanylbullen.tung.ui.common.localWeight
import io.github.kanylbullen.tung.ui.common.rememberNow
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner
import io.github.kanylbullen.tung.voice.SpeechErrorKind
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.map

private enum class Field { WEIGHT, REPS }

/**
 * The sheet open for a set's weight or reps. [firstTime]: the exercise was just
 * added with no history, so the weight sheet's Done opens the reps sheet, and
 * that one asks how many sets as well.
 */
private data class EditTarget(val setId: String, val field: Field, val firstTime: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveWorkoutScreen(
    workoutId: String,
    onBack: () -> Unit,
    /** The ongoing notification's "Finish" was tapped: open the finish dialog once, then call [onFinishAsked]. */
    askToFinish: Boolean = false,
    onFinishAsked: () -> Unit = {},
    onAddExercise: () -> Unit,
    onFinished: (workoutId: String, saved: Boolean) -> Unit,
    /** The workout was discarded from the menu (issue #81): nothing saved, back to Home. */
    onDiscarded: () -> Unit = {},
    /** "Ask the assistant" on words the voice grammar did not understand: the Assistant screen with them. */
    onAskAssistant: (String) -> Unit = {},
) {
    val container = appContainer()
    val vm: ActiveWorkoutViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                ActiveWorkoutViewModel(
                    workoutId = workoutId,
                    repo = container.workoutRepository,
                    commands = container.commands,
                    restTimer = container.restTimer,
                    savedState = createSavedStateHandle(),
                )
            }
        }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val discardedMessage = stringResource(R.string.workout_discarded)
    val currentOnFinished by rememberUpdatedState(onFinished)
    val currentOnDiscarded by rememberUpdatedState(onDiscarded)

    // Between sets the phone lies on a bench; the screen must not sleep.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Asked once, the first time a workout is on screen, for the ongoing notification and "Rest over".
    // Denied is fine: the workout, the countdown and the buzz still work.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is ActiveWorkoutEvent.Finished -> {
                    if (!event.saved) Toast.makeText(context, discardedMessage, Toast.LENGTH_LONG).show()
                    currentOnFinished(event.workoutId, event.saved)
                }
                is ActiveWorkoutEvent.Discarded -> currentOnDiscarded()
                ActiveWorkoutEvent.AskForNotifications -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
    }

    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var confirmFinish by remember { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    /** The workout exercise "Remove exercise" asks about, because it has logged sets; null when not asking. */
    var confirmRemoveId by rememberSaveable { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(askToFinish) {
        if (askToFinish) {
            confirmFinish = true
            onFinishAsked()
        }
    }
    // Voice (CLAUDE.md, "Voice"): the grammar in core turns what is heard into an intent, voiceAction
    // into what the ViewModel's own functions do. The controller runs on this screen's scope, so it
    // stops when the screen leaves; it also stops when the app goes to the background.
    val app = context.applicationContext as TungApp
    val voiceScope = rememberCoroutineScope()
    val voice = remember { VoiceController(app.createSpeechInput(), voiceScope) }
    val voiceState by voice.state.collectAsStateWithLifecycle()
    val aiConfigured by remember { container.aiSettings.config.map { it.configured } }.collectAsStateWithLifecycle(false)
    val locale = LocalConfiguration.current.locales[0]
    val language = VoiceLanguage.of(locale.toLanguageTag())
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(voice, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) voice.stop() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            voice.stop()
        }
    }
    val handleUtterance: suspend (String) -> VoiceEcho = { text ->
        val action = voiceAction(parseUtterance(text, language), vm.state.value)
        when (action) {
            is VoiceAction.Apply -> {
                vm.applyVoice(action).join()
                if (action.after == AfterVoice.ASK_FOR_REPS) editing = EditTarget(action.set.id, Field.REPS)
            }
            is VoiceAction.Select -> vm.selectFromVoice(action.exercise.workoutExerciseId, action.set.id)
            is VoiceAction.AddSet -> vm.addSet(action.exercise.workoutExerciseId)
            VoiceAction.Finish -> confirmFinish = true
            is VoiceAction.Rest -> vm.startRestFor(action.exercise)
            is VoiceAction.Problem, is VoiceAction.NotUnderstood -> Unit
        }
        action.echo
    }
    var pendingHandsFree by remember { mutableStateOf<Boolean?>(null) }
    fun startVoice(handsFree: Boolean) {
        if (handsFree) {
            voice.startHandsFree(locale, handleUtterance) {
                view.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK,
                )
            }
        } else {
            voice.listenOnce(locale, handleUtterance)
        }
    }
    // RECORD_AUDIO is asked for the first time the mic is used; denied, the echo says how to allow it.
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val handsFree = pendingHandsFree
        pendingHandsFree = null
        when {
            !granted -> voice.show(VoiceEcho.Failed(SpeechErrorKind.PERMISSION))
            handsFree != null -> startVoice(handsFree)
        }
    }
    fun onMic(handsFree: Boolean) {
        if (voice.active && !handsFree) {
            voice.stop()
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoice(handsFree)
        } else {
            pendingHandsFree = handsFree
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    var restControlsOpen by remember { mutableStateOf(false) }
    var restSheetFor by remember { mutableStateOf<ActiveExerciseUi?>(null) }
    // The exercise (its workout exercise id) whose progress sheet is open; survives rotation.
    var progressFor by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // The clock, not a counter: what is left of the rest is always computed from it.
    val now = rememberNow()
    val rest = state.rest?.takeIf { it.isRunningAt(now) }
    state.rest?.let { timer ->
        if (timer.isFinishedAt(now)) LaunchedEffect(timer) { vm.onRestOver(timer) }
    }
    LaunchedEffect(rest == null) { if (rest == null) restControlsOpen = false }

    val sticky = state.sticky
    val current = (sticky as? StickyButton.LogSet)?.current

    // The ViewModel asks for a scroll (a set added, an exercise added, the current set moved on).
    // A set or exercise that was just written may not be in the list yet; the effect waits for it.
    val scrollRequest = state.scrollRequest
    val scrollIndex = scrollRequest?.let { request ->
        val key = request.setId?.let(::setKey) ?: request.workoutExerciseId?.let(::headerKey)
        listKeys(state).indexOf(key).takeIf { it >= 0 }
    }
    LaunchedEffect(scrollRequest, scrollIndex) {
        if (scrollRequest == null || scrollIndex == null) return@LaunchedEffect
        // Laid out first: until then the list does not know the new item.
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > scrollIndex }
        when (scrollRequest.mode) {
            ScrollMode.IntoView -> listState.scrollIntoView(scrollIndex)
            ScrollMode.ToTop -> listState.animateScrollToItem(scrollIndex)
        }
        vm.onScrollHandled(scrollRequest)
    }

    // An exercise was just added with no history: its weight sheet opens at once, once its set is on screen.
    val firstTime = state.firstTimeRequest
    val firstTimeReady = firstTime != null && state.findSet(firstTime.setId) != null
    LaunchedEffect(firstTime, firstTimeReady) {
        if (firstTime == null || !firstTimeReady) return@LaunchedEffect
        editing = EditTarget(firstTime.setId, Field.WEIGHT, firstTime = true)
        vm.onFirstTimeHandled(firstTime)
    }

    Scaffold(
        containerColor = Tung.colors.background,
        topBar = {
            Column {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Tung.colors.background,
                    scrolledContainerColor = Tung.colors.background,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = {
                    if (!state.loading && !state.missing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val clock = clockText(now - state.startedAt)
                            val description = stringResource(R.string.workout_elapsed_description, clock)
                            Text(
                                clock,
                                style = Tung.numbers.set.copy(fontSize = Tung.numbers.set.fontSize * 0.8f),
                                modifier = Modifier.clearAndSetSemantics { contentDescription = description },
                            )
                            if (rest != null) {
                                Spacer(Modifier.width(12.dp))
                                RestCountdown(rest, now, onClick = { restControlsOpen = !restControlsOpen })
                            }
                        }
                    }
                },
                actions = {
                    if (!state.loading && !state.missing) {
                        TextButton(onClick = { confirmFinish = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(
                                stringResource(R.string.workout_finish),
                                style = MaterialTheme.typography.labelLarge,
                                color = Tung.colors.accentText,
                            )
                        }
                        // The way out that keeps nothing (issue #81), behind a menu so a
                        // thumb aiming at Finish cannot land on it.
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                                containerColor = Tung.colors.raised,
                                shape = TungCorner,
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.workout_discard)) },
                                    onClick = { menuOpen = false; confirmDiscard = true },
                                )
                            }
                        }
                    }
                },
            )
            if (rest != null && restControlsOpen) {
                RestControls(onSkip = vm::skipRest, onAdd = vm::addRest)
            }
            }
        },
        bottomBar = {
            if (!state.loading && !state.missing && sticky != null) {
                val echo = voiceState.echo
                val askable = echo is VoiceEcho.NotUnderstood && aiConfigured
                val echoShown = echo != null &&
                    now - voiceState.echoAt < if (askable) VOICE_ECHO_WITH_ACTION_MILLIS else VOICE_ECHO_MILLIS
                val echoText = if (echoShown && echo != null) voiceEchoText(echo) else null
                val line = voiceState.partial
                    ?: echoText
                    ?: if (voiceState.listening && !voiceState.handsFree) stringResource(R.string.voice_listening) else null
                LogSetBar(
                    sticky = sticky,
                    voice = voiceState,
                    line = line,
                    lineMuted = voiceState.partial != null || echoText == null,
                    askAssistant = if (askable && echoText != null && line == echoText) {
                        {
                            voice.stop()
                            voice.dismissEcho()
                            onAskAssistant((echo as VoiceEcho.NotUnderstood).text)
                        }
                    } else {
                        null
                    },
                    onMic = { onMic(handsFree = false) },
                    onMicLongPress = { onMic(handsFree = true) },
                    onStopHandsFree = voice::stop,
                    onClick = {
                        when (sticky) {
                            is StickyButton.LogSet -> {
                                val c = sticky.current
                                // Logging a set without reps would just be removed on finish, so ask for reps first.
                                if (c.set.reps <= 0) {
                                    editing = EditTarget(c.set.id, Field.REPS)
                                } else {
                                    vm.logSet(c.exercise, c.set)
                                }
                            }
                            is StickyButton.AddSet -> vm.addSetFromButton(sticky.exercise.workoutExerciseId)
                        }
                    },
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding))
            state.missing -> Text(
                stringResource(R.string.workout_not_found),
                style = MaterialTheme.typography.bodyLarge,
                color = Tung.colors.muted,
                modifier = Modifier.padding(padding).padding(20.dp),
            )
            else -> WorkoutList(
                state = state,
                listState = listState,
                contentPadding = padding,
                currentSetId = current?.set?.id,
                onSelectExercise = vm::selectExercise,
                onSelectSet = vm::selectSet,
                onEdit = { setId, field -> editing = EditTarget(setId, field) },
                onToggle = { exercise, set ->
                    // Ticking a set without reps would just be removed on finish, so ask for reps first.
                    if (!set.completed && set.reps <= 0) editing = EditTarget(set.id, Field.REPS)
                    else vm.setCompleted(exercise, set.id, !set.completed)
                },
                onDeleteSet = vm::deleteSet,
                onAddSet = vm::addSet,
                onRemoveExercise = { id ->
                    // Logged sets are training data: ask before they go. An exercise with none goes at once.
                    val exercise = state.exercises.firstOrNull { it.workoutExerciseId == id }
                    if (exercise != null && exercise.sets.any { isLogged(it.completed, it.reps) }) confirmRemoveId = id
                    else vm.removeExercise(id)
                },
                onRestTimer = { restSheetFor = it },
                onProgress = { progressFor = it.workoutExerciseId },
                onAddExercise = onAddExercise,
            )
        }
    }

    editing?.let { target ->
        val exercise = state.findExerciseOf(target.setId)
        val set = exercise?.sets?.firstOrNull { it.id == target.setId }
        if (exercise == null || set == null) {
            LaunchedEffect(target) { editing = null }
        } else {
            // Keyed on the target: the first-time flow goes straight from the weight sheet to the reps sheet.
            key(target) {
                var setCount by remember { mutableIntStateOf(FIRST_TIME_DEFAULT_SETS) }
                NumberInputSheet(
                    spec = when (target.field) {
                        Field.WEIGHT -> weightSpec(localWeight(set.weightKg))
                        Field.REPS -> repsSpec(set.reps.toString())
                    },
                    // Only offered when another un-ticked set would take the value.
                    showApplyToAll = exercise.sets.any { it.id != set.id && !it.completed },
                    onDismiss = { editing = null },
                    onConfirm = { text, applyToAll ->
                        val weId = exercise.workoutExerciseId
                        editing = null
                        when (target.field) {
                            Field.WEIGHT -> parseWeight(text)?.let {
                                when {
                                    target.firstTime -> {
                                        vm.setFirstTimeWeight(weId, set.id, it)
                                        editing = EditTarget(set.id, Field.REPS, firstTime = true)
                                    }
                                    applyToAll -> vm.setWeightForOpenSets(weId, set.id, it)
                                    else -> vm.setWeight(weId, set.id, it)
                                }
                            }
                            Field.REPS -> parseReps(text)?.let {
                                when {
                                    target.firstTime -> vm.finishFirstTime(weId, set.id, it, setCount)
                                    applyToAll -> vm.setRepsForOpenSets(weId, set.id, it)
                                    else -> vm.setReps(weId, set.id, it)
                                }
                            }
                        }
                    },
                    aboveDone = if (target.firstTime && target.field == Field.REPS) {
                        { SetCountChips(selected = setCount, onSelect = { setCount = it }) }
                    } else {
                        null
                    },
                )
            }
        }
    }

    progressFor?.let { weId ->
        val exercise = state.exercises.firstOrNull { it.workoutExerciseId == weId }
        if (exercise == null) {
            LaunchedEffect(weId) { progressFor = null }
        } else {
            // The set "Log set" would log if it is this exercise's, else the exercise's first open set.
            val set = current?.takeIf { it.exercise.workoutExerciseId == weId }?.set
                ?: exercise.sets.firstOrNull { !it.completed }
            ProgressSheet(
                exerciseId = exercise.exerciseId,
                exerciseName = exerciseDisplayName(exercise.nameKey, exercise.name),
                proposed = set?.let { LoggedSet(it.weightKg, it.reps) },
                onDismiss = { progressFor = null },
            )
        }
    }

    restSheetFor?.let { exercise ->
        RestTimerSheet(
            exerciseName = exerciseDisplayName(exercise.nameKey, exercise.name),
            initialSeconds = exercise.restSeconds,
            onDismiss = { restSheetFor = null },
            onConfirm = { seconds ->
                vm.setRestSeconds(exercise.exerciseId, seconds)
                restSheetFor = null
            },
        )
    }

    if (confirmFinish) {
        val empty = state.loggedSetCount == 0
        // What Finish removes: the sets that are not logged, and exercises with no set at all.
        val unlogged = state.exercises.sumOf { e -> e.sets.count { !isLogged(it.completed, it.reps) } }
        val emptyExercises = state.exercises.count { it.sets.isEmpty() }
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            containerColor = Tung.colors.raised,
            tonalElevation = 0.dp,
            title = { Text(stringResource(R.string.workout_finish_title)) },
            text = {
                val body = when {
                    empty -> stringResource(R.string.workout_finish_body_empty)
                    unlogged == 0 && emptyExercises == 0 -> stringResource(R.string.workout_finish_body_clean)
                    else -> listOfNotNull(
                        unlogged.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.workout_finish_unlogged, it, it) },
                        emptyExercises.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.workout_finish_empty_exercises, it, it) },
                    ).joinToString(" ")
                }
                Text(body, color = Tung.colors.muted)
            },
            confirmButton = {
                TextButton(onClick = { confirmFinish = false; vm.finish() }) {
                    Text(stringResource(R.string.workout_finish_confirm), color = Tung.colors.accentText)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmFinish = false }) {
                    Text(stringResource(R.string.workout_keep_training), color = Tung.colors.text)
                }
            },
        )
    }

    if (confirmDiscard) {
        DiscardWorkoutDialog(
            onConfirm = { confirmDiscard = false; vm.discard() },
            onDismiss = { confirmDiscard = false },
        )
    }

    val removing = confirmRemoveId?.let { id -> state.exercises.firstOrNull { it.workoutExerciseId == id } }
    if (removing != null) {
        val exercise = removing
        val logged = exercise.sets.count { isLogged(it.completed, it.reps) }
        AlertDialog(
            onDismissRequest = { confirmRemoveId = null },
            containerColor = Tung.colors.raised,
            tonalElevation = 0.dp,
            title = {
                Text(pluralStringResource(R.plurals.workout_remove_exercise_title, logged, exerciseDisplayName(exercise.nameKey, exercise.name), logged))
            },
            text = { Text(stringResource(R.string.workout_remove_exercise_body), color = Tung.colors.muted) },
            confirmButton = {
                TextButton(onClick = { confirmRemoveId = null; vm.removeExercise(exercise.workoutExerciseId) }) {
                    Text(stringResource(R.string.workout_remove_exercise_confirm), color = Tung.colors.accentText)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveId = null }) {
                    Text(stringResource(R.string.action_cancel), color = Tung.colors.text)
                }
            },
        )
    }
}

/**
 * The big button above the system navigation: always under the thumb, wherever
 * the list is scrolled, which a button at the end of the list would not be.
 * The mic button sits left of it, and above both the voice line: what was
 * heard or understood, and the "Listening" chip while hands-free mode is on.
 * With every set logged it reads "Add set · Bench press · 80 × 8" for the
 * exercise just logged (issue #64): logging never adds a set by itself.
 */
@Composable
private fun LogSetBar(
    sticky: StickyButton,
    voice: VoiceUiState,
    line: String?,
    lineMuted: Boolean,
    askAssistant: (() -> Unit)?,
    onMic: () -> Unit,
    onMicLongPress: () -> Unit,
    onStopHandsFree: () -> Unit,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Tung.colors.background)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (voice.handsFree) HandsFreeChip(onStop = onStopHandsFree)
        if (line != null) {
            VoiceEchoLine(
                text = line,
                muted = lineMuted,
                actionLabel = askAssistant?.let { stringResource(R.string.voice_ask_assistant) },
                onAction = { askAssistant?.invoke() },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MicButton(active = voice.listening || voice.handsFree, onTap = onMic, onLongPress = onMicLongPress)
            val text = when (sticky) {
                is StickyButton.LogSet -> {
                    val set = sticky.current.set
                    val reps = if (set.reps > 0) set.reps.toString() else stringResource(R.string.workout_reps_empty)
                    val name = exerciseDisplayName(sticky.current.exercise.nameKey, sticky.current.exercise.name)
                    stringResource(R.string.workout_log_set, name, localWeight(set.weightKg), reps)
                }
                is StickyButton.AddSet -> {
                    val name = exerciseDisplayName(sticky.exercise.nameKey, sticky.exercise.name)
                    val set = sticky.copyOf
                    if (set == null) {
                        stringResource(R.string.workout_sticky_add_set_empty, name)
                    } else {
                        val reps = if (set.reps > 0) set.reps.toString() else stringResource(R.string.workout_reps_empty)
                        stringResource(R.string.workout_sticky_add_set, name, localWeight(set.weightKg), reps)
                    }
                }
            }
            PrimaryButton(text = text, onClick = onClick, modifier = Modifier.weight(1f))
        }
    }
}

/** "1:30" counting down next to the elapsed time; a tap shows Skip and +30 s. */
@Composable
private fun RestCountdown(timer: RestTimer, now: Long, onClick: () -> Unit) {
    // Rounded up, so 89.4 s left reads 1:30 and 0:00 never shows; capped, so a clock a tick behind never shows 1:31.
    val left = clockText(minOf(timer.remainingAt(now) + 999, timer.durationMillis))
    val title = stringResource(R.string.rest_title)
    val remaining = stringResource(R.string.rest_remaining, left)
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(TungCorner)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = title
                stateDescription = remaining
                role = Role.Button
            }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            left,
            style = Tung.numbers.set.copy(fontSize = Tung.numbers.set.fontSize * 0.8f),
            color = Tung.colors.accentText,
        )
    }
}

@Composable
private fun RestControls(onSkip: () -> Unit, onAdd: () -> Unit) {
    val addDescription = stringResource(R.string.rest_add_description)
    Row(
        modifier = Modifier.fillMaxWidth().background(Tung.colors.background).padding(start = 56.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.rest_skip), style = MaterialTheme.typography.labelLarge, color = Tung.colors.text)
        }
        TextButton(
            onClick = onAdd,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = addDescription },
        ) {
            Text(stringResource(R.string.rest_add), style = MaterialTheme.typography.labelLarge, color = Tung.colors.text)
        }
    }
}

private const val EMPTY_KEY = "empty"
private const val ADD_EXERCISE_KEY = "add-exercise"

private fun headerKey(workoutExerciseId: String) = "we-$workoutExerciseId"

private fun setKey(setId: String) = "set-$setId"

private fun addSetKey(workoutExerciseId: String) = "add-$workoutExerciseId"

/** The keys of the list's items in order; [WorkoutList] emits exactly these. */
private fun listKeys(state: ActiveWorkoutUiState): List<String> = buildList {
    if (state.exercises.isEmpty()) add(EMPTY_KEY)
    state.exercises.forEach { exercise ->
        add(headerKey(exercise.workoutExerciseId))
        exercise.sets.forEach { add(setKey(it.id)) }
        add(addSetKey(exercise.workoutExerciseId))
    }
    add(ADD_EXERCISE_KEY)
}

/**
 * Scrolls only when the item is not wholly on screen (between the top bar and
 * the Log set button). Partly visible: just enough to show all of it. Out of
 * sight: to the item, with the row above it in view too.
 */
private suspend fun LazyListState.scrollIntoView(index: Int) {
    val info = layoutInfo
    val top = info.viewportStartOffset + info.beforeContentPadding
    val bottom = info.viewportEndOffset - info.afterContentPadding
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    when {
        item == null -> animateScrollToItem((index - 1).coerceAtLeast(0))
        item.offset < top -> animateScrollBy((item.offset - top).toFloat())
        item.offset + item.size > bottom -> animateScrollBy((minOf(item.offset - top, item.offset + item.size - bottom)).toFloat())
    }
}

@Composable
private fun WorkoutList(
    state: ActiveWorkoutUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    currentSetId: String?,
    onSelectExercise: (String) -> Unit,
    onSelectSet: (workoutExerciseId: String, setId: String) -> Unit,
    onEdit: (String, Field) -> Unit,
    onToggle: (ActiveExerciseUi, ActiveSetUi) -> Unit,
    onDeleteSet: (String) -> Unit,
    onAddSet: (String) -> Unit,
    onRemoveExercise: (String) -> Unit,
    onRestTimer: (ActiveExerciseUi) -> Unit,
    onProgress: (ActiveExerciseUi) -> Unit,
    onAddExercise: () -> Unit,
) {
    // The set row a swipe has opened (its Delete revealed, issue #81): one at a
    // time, and closed by a scroll or by a tap on anything else in the list.
    var openSetId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) openSetId = null
    }
    fun closing(block: () -> Unit): () -> Unit = { openSetId = null; block() }
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize(),
    ) {
        if (state.exercises.isEmpty()) {
            item(key = EMPTY_KEY) {
                Text(
                    stringResource(R.string.workout_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tung.colors.muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }
        state.exercises.forEach { exercise ->
            item(key = headerKey(exercise.workoutExerciseId)) {
                ExerciseHeader(
                    exerciseDisplayName(exercise.nameKey, exercise.name),
                    // Only an exercise with a set left to log can take the current set.
                    onSelect = if (exercise.sets.any { !it.completed }) {
                        closing { onSelectExercise(exercise.workoutExerciseId) }
                    } else {
                        null
                    },
                    onRestTimer = closing { onRestTimer(exercise) },
                    onProgress = closing { onProgress(exercise) },
                    onRemove = closing { onRemoveExercise(exercise.workoutExerciseId) },
                )
            }
            items(exercise.sets, key = { setKey(it.id) }) { set ->
                SwipeableSetRow(
                    set = set,
                    isCurrent = set.id == currentSetId,
                    open = openSetId == set.id,
                    onOpenChange = { isOpen ->
                        if (isOpen) openSetId = set.id else if (openSetId == set.id) openSetId = null
                    },
                    onSelect = closing { onSelectSet(exercise.workoutExerciseId, set.id) },
                    onEditWeight = closing { onEdit(set.id, Field.WEIGHT) },
                    onEditReps = closing { onEdit(set.id, Field.REPS) },
                    onToggle = closing { onToggle(exercise, set) },
                    onDelete = closing { onDeleteSet(set.id) },
                )
            }
            item(key = addSetKey(exercise.workoutExerciseId)) {
                val description = stringResource(
                    R.string.workout_add_set_description,
                    exerciseDisplayName(exercise.nameKey, exercise.name),
                )
                TextButton(
                    onClick = closing { onAddSet(exercise.workoutExerciseId) },
                    modifier = Modifier
                        .padding(start = 8.dp, bottom = 12.dp)
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = description },
                ) {
                    Text(
                        stringResource(R.string.workout_add_set),
                        style = MaterialTheme.typography.labelLarge,
                        color = Tung.colors.text,
                    )
                }
            }
        }
        item(key = ADD_EXERCISE_KEY) {
            val text = stringResource(R.string.workout_add_exercise)
            Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                if (state.exercises.isEmpty()) {
                    PrimaryButton(text = text, onClick = onAddExercise)
                } else {
                    SecondaryButton(text = text, onClick = onAddExercise)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ExerciseHeader(
    name: String,
    onSelect: (() -> Unit)?,
    onRestTimer: () -> Unit,
    onProgress: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val selectDescription = stringResource(R.string.workout_select_exercise, name)
    Row(
        modifier = Modifier.fillMaxWidth().padding(end = 4.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The name is the way to switch exercises: a tap makes its first open set the current set.
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .then(
                    if (onSelect != null) {
                        Modifier
                            .clickable(role = Role.Button, onClick = onSelect)
                            .semantics { contentDescription = selectDescription }
                    } else {
                        Modifier
                    },
                )
                .padding(start = 20.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(name, style = MaterialTheme.typography.titleLarge, color = Tung.colors.text)
        }
        IconButton(onClick = onProgress) {
            Icon(
                painterResource(R.drawable.ic_trending_up),
                contentDescription = stringResource(R.string.workout_progress_for, name),
                tint = Tung.colors.muted,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.workout_exercise_menu, name),
                    tint = Tung.colors.muted,
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = Tung.colors.raised,
                shape = TungCorner,
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workout_rest_timer)) },
                    onClick = { menuOpen = false; onRestTimer() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workout_remove_exercise)) },
                    onClick = { menuOpen = false; onRemove() },
                )
            }
        }
    }
}

/** A set row's swipe position: closed, or open with Delete revealed behind it. */
private enum class RowSwipe { Closed, Open }

/** How far the row slides left to reveal the Delete button, which is this wide. */
private val REVEAL_WIDTH = 96.dp

/**
 * A set row that a swipe to the left opens, settling with a Delete button
 * revealed behind it (issue #81); a tap on the button deletes, a tap on the
 * row or a swipe back closes it. Before, a full swipe deleted the set at
 * once, which happened by accident. The list keeps one row open at a time
 * through [open] and [onOpenChange]. The accessible way to delete is still
 * the "Delete set" action on every part of the row a screen reader focuses;
 * the button exists only while revealed, so TalkBack never meets "Delete"
 * on every set.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeableSetRow(
    set: ActiveSetUi,
    isCurrent: Boolean,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onSelect: () -> Unit,
    onEditWeight: () -> Unit,
    onEditReps: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val currentOnDelete by rememberUpdatedState(onDelete)
    val currentOnOpenChange by rememberUpdatedState(onOpenChange)
    val density = LocalDensity.current
    val revealPx = with(density) { REVEAL_WIDTH.toPx() }
    val swipe = remember(revealPx) {
        AnchoredDraggableState(
            initialValue = RowSwipe.Closed,
            anchors = DraggableAnchors {
                RowSwipe.Closed at 0f
                RowSwipe.Open at -revealPx
            },
            positionalThreshold = { distance -> distance * 0.5f },
            velocityThreshold = { with(density) { 125.dp.toPx() } },
            snapAnimationSpec = tween(),
            decayAnimationSpec = exponentialDecay(),
        )
    }
    // The list closes this row when another opens or something else is tapped...
    LaunchedEffect(open) {
        // Also a row still animating open: targetValue is Open before currentValue is.
        if (!open && (swipe.currentValue == RowSwipe.Open || swipe.targetValue == RowSwipe.Open)) {
            swipe.animateTo(RowSwipe.Closed)
        }
    }
    // ...and hears when a swipe settles it open or closed.
    LaunchedEffect(swipe) {
        snapshotFlow { swipe.settledValue }.collect { currentOnOpenChange(it == RowSwipe.Open) }
    }
    val deleteLabel = stringResource(R.string.workout_delete_set)
    // The accessible way to delete: a custom action on every part of the row a
    // screen reader can focus. The row container itself is never focused, so an
    // action there would be out of reach.
    val deleteAction = remember(deleteLabel) {
        listOf(CustomAccessibilityAction(deleteLabel) { currentOnDelete(); true })
    }
    // Only the flip recomposes the row; the offset itself is applied in the layout lambda below.
    val revealed by remember(swipe) { derivedStateOf { swipe.requireOffset() < 0f } }
    Box(Modifier.fillMaxWidth()) {
        if (revealed) {
            val description = stringResource(R.string.workout_delete_set_number, set.number)
            Box(
                modifier = Modifier.matchParentSize().background(Tung.colors.danger),
                contentAlignment = Alignment.CenterEnd,
            ) {
                TextButton(
                    onClick = { currentOnDelete() },
                    shape = TungCorner,
                    modifier = Modifier
                        .width(REVEAL_WIDTH)
                        .fillMaxHeight()
                        .semantics { contentDescription = description },
                ) {
                    Text(
                        stringResource(R.string.workout_delete),
                        style = MaterialTheme.typography.labelLarge,
                        color = Tung.colors.onDanger,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(swipe.requireOffset().roundToInt(), 0) }
                .anchoredDraggable(swipe, Orientation.Horizontal),
        ) {
            SetRow(set, isCurrent, deleteAction, onSelect, onEditWeight, onEditReps, onToggle)
            if (open) {
                // While open, a tap on the row closes it instead of reaching a cell.
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) { detectTapGestures { currentOnOpenChange(false) } },
                )
            }
        }
    }
}

@Composable
private fun SetRow(
    set: ActiveSetUi,
    isCurrent: Boolean,
    deleteAction: List<CustomAccessibilityAction>,
    onSelect: () -> Unit,
    onEditWeight: () -> Unit,
    onEditReps: () -> Unit,
    onToggle: () -> Unit,
) {
    val weight = localWeight(set.weightKg)
    val reps = if (set.reps > 0) set.reps.toString() else stringResource(R.string.workout_reps_empty)
    val setDescription = stringResource(R.string.workout_set_number_description, set.number)
    val selectDescription = stringResource(R.string.workout_select_set, set.number)
    val currentState = stringResource(R.string.workout_current_set)
    val accent = Tung.colors.accent
    // Any other un-ticked set can be made the current one by tapping its number.
    val selectable = !set.completed && !isCurrent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The current set, the one "Log set" acts on: the raised background, an accent bar at the row's
            // left edge and its number in the accent, so it reads in poor light and under a case's lip.
            .background(if (isCurrent) Tung.colors.raised else Tung.colors.background)
            .drawBehind { if (isCurrent) drawRect(accent, size = Size(CURRENT_BAR_WIDTH.toPx(), size.height)) }
            .heightIn(min = 72.dp)
            .padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(48.dp)
                .heightIn(min = 48.dp)
                .then(if (selectable) Modifier.clickable(role = Role.Button, onClick = onSelect) else Modifier)
                .clearAndSetSemantics {
                    contentDescription = if (selectable) selectDescription else setDescription
                    if (selectable) role = Role.Button
                    if (isCurrent) stateDescription = currentState
                    customActions = deleteAction
                    if (selectable) onClick { onSelect(); true }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                set.number.toString(),
                style = Tung.numbers.set.copy(fontSize = Tung.numbers.set.fontSize * 0.7f),
                color = if (isCurrent) Tung.colors.accentText else Tung.colors.muted,
            )
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NumberCell(
                    value = weight,
                    unit = stringResource(R.string.workout_unit_kg),
                    description = stringResource(R.string.workout_edit_weight, weight),
                    actions = deleteAction,
                    onClick = onEditWeight,
                )
                Text(stringResource(R.string.times_sign), style = Tung.numbers.inline, color = Tung.colors.muted)
                NumberCell(
                    value = reps,
                    unit = stringResource(R.string.workout_unit_reps),
                    description = stringResource(R.string.workout_edit_reps, reps),
                    actions = deleteAction,
                    onClick = onEditReps,
                )
            }
            set.previous?.let { prev ->
                Text(
                    stringResource(R.string.workout_previous, localWeight(prev.weightKg), prev.reps),
                    style = Tung.numbers.inline,
                    color = Tung.colors.muted,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        DoneCheck(number = set.number, completed = set.completed, actions = deleteAction, onToggle = onToggle)
    }
}

@Composable
private fun NumberCell(
    value: String,
    unit: String,
    description: String,
    actions: List<CustomAccessibilityAction>,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(TungCorner)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                customActions = actions
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(value, style = Tung.numbers.set, color = Tung.colors.text, modifier = Modifier.alignByBaseline())
        Text(unit, style = Tung.numbers.inline, color = Tung.colors.muted, modifier = Modifier.alignByBaseline())
    }
}

@Composable
private fun DoneCheck(
    number: Int,
    completed: Boolean,
    actions: List<CustomAccessibilityAction>,
    onToggle: () -> Unit,
) {
    val fill by animateColorAsState(if (completed) Tung.colors.accent else Color.Transparent, label = "check-fill")
    val ring by animateColorAsState(if (completed) Tung.colors.accent else Tung.colors.muted, label = "check-ring")
    val tint by animateColorAsState(if (completed) Tung.colors.onAccent else Color.Transparent, label = "check-tint")
    val description = stringResource(
        if (completed) R.string.workout_mark_undone else R.string.workout_mark_done,
        number,
    )
    Box(
        modifier = Modifier
            .size(56.dp)
            .toggleable(value = completed, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics {
                contentDescription = description
                customActions = actions
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(fill, CircleShape)
                .border(2.dp, ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
    }
}


private val CURRENT_BAR_WIDTH = 3.dp

/** "Increase by 1 kilo", "Increase by 2.5 kilos": a whole step picks its plural, a fraction is "other". */
private fun stepDescription(context: Context, @PluralsRes id: Int, stepKg: Double): String {
    val quantity = if (stepKg % 1.0 == 0.0) stepKg.toInt() else 2
    return context.resources.getQuantityString(id, quantity, localWeight(stepKg))
}

@Composable
private fun weightSpec(initial: String): NumberInputSpec {
    val context = LocalContext.current
    return NumberInputSpec(
        title = stringResource(R.string.input_weight_title),
        unit = stringResource(R.string.workout_unit_kg),
        initialText = initial,
        keyboardType = KeyboardType.Decimal,
        fieldDescription = stringResource(R.string.input_field_weight),
        minusDescription = { text ->
            stepDescription(context, R.plurals.input_minus_weight, WEIGHT_RULER.stepBelow(parseWeight(text) ?: 0.0))
        },
        plusDescription = { text ->
            stepDescription(context, R.plurals.input_plus_weight, WEIGHT_RULER.stepAt(parseWeight(text) ?: 0.0))
        },
        hint = stringResource(R.string.input_hint),
        errorText = stringResource(R.string.input_error_weight),
        doneText = stringResource(R.string.input_done),
        minusText = stringResource(R.string.input_minus),
        plusText = stringResource(R.string.input_plus),
        isValid = { parseWeight(it) != null },
        step = { text, steps -> localWeight(stepWeight(parseWeight(text) ?: 0.0, steps)) },
        sanitize = { text -> text.filter { it.isDigit() || it == ',' || it == '.' }.take(6) },
        ruler = WEIGHT_RULER,
        toValue = ::parseWeight,
        fromValue = ::localWeight,
        rulerDescription = stringResource(R.string.input_ruler_weight),
        rulerState = { context.getString(R.string.input_ruler_weight_state, localWeight(it)) },
    )
}

/**
 * The reps sheet. A set takes 0 and up; a routine's target reps ([minimum] 1,
 * with its own [title] and [errorText]) use the same sheet.
 */
@Composable
internal fun repsSpec(
    initial: String,
    title: String = stringResource(R.string.input_reps_title),
    minimum: Int = 0,
    errorText: String = stringResource(R.string.input_error_reps),
): NumberInputSpec {
    val context = LocalContext.current
    val minusReps = stringResource(R.string.input_minus_reps)
    val plusReps = stringResource(R.string.input_plus_reps)
    return NumberInputSpec(
        title = title,
        unit = stringResource(R.string.workout_unit_reps),
        initialText = initial,
        keyboardType = KeyboardType.Number,
        fieldDescription = stringResource(R.string.input_field_reps),
        minusDescription = { _ -> minusReps },
        plusDescription = { _ -> plusReps },
        hint = stringResource(R.string.input_hint),
        errorText = errorText,
        doneText = stringResource(R.string.input_done),
        minusText = stringResource(R.string.input_minus),
        plusText = stringResource(R.string.input_plus),
        isValid = { text -> parseReps(text)?.let { it >= minimum } == true },
        step = { text, steps -> stepReps(parseReps(text) ?: 0, steps).coerceAtLeast(minimum).toString() },
        sanitize = { text -> text.filter { it.isDigit() }.take(3) },
        ruler = REPS_RULER,
        toValue = { parseReps(it)?.toDouble() },
        fromValue = { it.roundToInt().toString() },
        rulerDescription = stringResource(R.string.input_ruler_reps),
        rulerState = { it.roundToInt().let { n -> context.resources.getQuantityString(R.plurals.input_ruler_reps_state, n, n) } },
    )
}
