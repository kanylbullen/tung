package io.github.kanylbullen.tung.ui.workout

import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.stringResource
import io.github.kanylbullen.tung.ui.resources.*
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
import io.github.kanylbullen.tung.ui.common.KeepScreenOn
import org.jetbrains.compose.resources.painterResource
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import org.jetbrains.compose.resources.pluralStringResource
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kanylbullen.tung.core.LoggedSet
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.kanylbullen.tung.core.RestTimer
import io.github.kanylbullen.tung.core.WEIGHT_RULER
import io.github.kanylbullen.tung.core.WORKOUT_CLOCK_SKEW_MILLIS
import io.github.kanylbullen.tung.core.isLogged
import io.github.kanylbullen.tung.core.MAX_FINISH_DURATION_MILLIS
import io.github.kanylbullen.tung.core.finishDurationPreset
import io.github.kanylbullen.tung.core.isPastWorkout
import io.github.kanylbullen.tung.core.parseReps
import io.github.kanylbullen.tung.core.parseWeight
import io.github.kanylbullen.tung.core.stepWeight
import io.github.kanylbullen.tung.core.voice.VoiceLanguage
import io.github.kanylbullen.tung.core.voice.parseUtterance
import io.github.kanylbullen.tung.ui.common.PrimaryButton
import io.github.kanylbullen.tung.ui.common.SecondaryButton
import io.github.kanylbullen.tung.ui.common.DurationStepper
import io.github.kanylbullen.tung.ui.common.WORKOUT_TIME_STEP_MILLIS
import io.github.kanylbullen.tung.ui.common.clockText
import io.github.kanylbullen.tung.ui.common.workoutStartText
import io.github.kanylbullen.tung.ui.common.exerciseDisplayName
import io.github.kanylbullen.tung.ui.common.localWeight
import io.github.kanylbullen.tung.ui.common.rememberNow
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner
import io.github.kanylbullen.tung.voice.SpeechErrorKind
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

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
    vm: ActiveWorkoutViewModel,
    platform: WorkoutPlatform,
    /** Whether the in-app assistant is set up, for "Ask the assistant" on words the grammar missed. */
    assistantConfigured: Boolean,
    /** The progress sheet for one exercise (the phone's `ProgressSheet`): id, displayed name, the set it would log, dismiss. */
    progressSheet: @Composable (exerciseId: String, exerciseName: String, proposed: LoggedSet?, onDismiss: () -> Unit) -> Unit,
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
    val state by vm.state.collectAsStateWithLifecycle()
    val loggedAfterTheFact by vm.loggedAfterTheFact.collectAsStateWithLifecycle()
    val discardedMessage = stringResource(Res.string.workout_discarded)
    val currentOnFinished by rememberUpdatedState(onFinished)
    val currentOnDiscarded by rememberUpdatedState(onDiscarded)

    // Between sets the phone lies on a bench; the screen must not sleep.
    KeepScreenOn()

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is ActiveWorkoutEvent.Finished -> {
                    if (!event.saved) platform.showMessage(discardedMessage)
                    currentOnFinished(event.workoutId, event.saved)
                }
                is ActiveWorkoutEvent.Discarded -> currentOnDiscarded()
                // Asked once, the first time a workout is on screen, for the ongoing notification and
                // "Rest over". Denied is fine: the workout, the countdown and the buzz still work.
                ActiveWorkoutEvent.AskForNotifications -> platform.askForNotifications()
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
    val voiceScope = rememberCoroutineScope()
    val voice = remember { VoiceController(platform.speech, voiceScope) }
    val voiceState by voice.state.collectAsStateWithLifecycle()
    val aiConfigured = assistantConfigured
    // The language the app shows ("en", "sv"), never the phone's locale: a Pixel in English with a
    // Swedish region is en-SE-u-fw-mon-…, which no recogniser has a language pack for.
    val languageTag = stringResource(Res.string.voice_language_tag)
    val language = VoiceLanguage.of(languageTag)
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
        var echo = action.echo
        when (action) {
            is VoiceAction.Apply -> {
                val logged = vm.applyVoice(action).await()
                if (action.after == AfterVoice.ASK_FOR_REPS) editing = EditTarget(action.set.id, Field.REPS)
                // `log_set` refused the set (it changed under the screen): never echo it as logged.
                if (!logged) echo = VoiceEcho.Problem(VoiceProblem.NOT_LOGGED, action.exercise.name, action.exercise.nameKey)
            }
            is VoiceAction.Select -> vm.selectFromVoice(action.exercise.workoutExerciseId, action.set.id)
            is VoiceAction.AddSet -> vm.addSet(action.exercise.workoutExerciseId)
            VoiceAction.Finish -> confirmFinish = true
            is VoiceAction.Rest -> vm.startRestFor(action.exercise)
            is VoiceAction.Problem, is VoiceAction.NotUnderstood -> Unit
        }
        echo
    }
    fun startVoice(handsFree: Boolean) {
        if (handsFree) {
            voice.startHandsFree(languageTag, handleUtterance) { platform.confirmHaptic() }
        } else {
            voice.listenOnce(languageTag, handleUtterance)
        }
    }
    // The microphone is asked for the first time it is used; denied, the echo says how to allow it.
    var pendingHandsFree by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(platform) {
        platform.microphoneAnswers.collect { granted ->
            val handsFree = pendingHandsFree
            pendingHandsFree = null
            when {
                !granted -> voice.show(VoiceEcho.Failed(SpeechErrorKind.PERMISSION))
                handsFree != null -> startVoice(handsFree)
            }
        }
    }
    fun onMic(handsFree: Boolean) {
        if (voice.active && !handsFree) {
            voice.stop()
            return
        }
        if (platform.microphoneAllowed()) {
            startVoice(handsFree)
        } else {
            pendingHandsFree = handsFree
            platform.askForMicrophone()
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                title = {
                    if (!state.loading && !state.missing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (loggedAfterTheFact || isPastWorkout(state.startedAt, now)) {
                                // Logged after the fact: when it started, not a timer running since then.
                                val started = workoutStartText(state.startedAt)
                                val description = stringResource(Res.string.workout_started_description, started)
                                Text(
                                    started,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics { contentDescription = description },
                                )
                            } else {
                                val clock = clockText(now - state.startedAt)
                                val description = stringResource(Res.string.workout_elapsed_description, clock)
                                Text(
                                    clock,
                                    style = Tung.numbers.set.copy(fontSize = Tung.numbers.set.fontSize * 0.8f),
                                    modifier = Modifier.clearAndSetSemantics { contentDescription = description },
                                )
                            }
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
                                stringResource(Res.string.workout_finish),
                                style = MaterialTheme.typography.labelLarge,
                                color = Tung.colors.accentText,
                            )
                        }
                        // The way out that keeps nothing (issue #81), behind a menu so a
                        // thumb aiming at Finish cannot land on it.
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.action_more))
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                                containerColor = Tung.colors.raised,
                                shape = TungCorner,
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.workout_discard)) },
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
                    ?: if (voiceState.listening && !voiceState.handsFree) stringResource(Res.string.voice_listening) else null
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
                stringResource(Res.string.workout_not_found),
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
            progressSheet(
                exercise.exerciseId,
                exerciseDisplayName(exercise.nameKey, exercise.name),
                set?.let { LoggedSet(it.weightKg, it.reps) },
            ) { progressFor = null }
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
        // A past workout ends when it really did: its start plus how long it lasted. Decided once,
        // when the dialog opens, so the duration never appears under the thumb mid-dialog.
        val past = rememberSaveable { loggedAfterTheFact || isPastWorkout(state.startedAt, now) }
        var duration by rememberSaveable { mutableLongStateOf(finishDurationPreset(state.startedAt, now, loggedAfterTheFact)) }
        val longest = minOf(MAX_FINISH_DURATION_MILLIS, now + WORKOUT_CLOCK_SKEW_MILLIS - state.startedAt)
        val lasted = duration.coerceAtMost(longest)
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            containerColor = Tung.colors.raised,
            tonalElevation = 0.dp,
            title = { Text(stringResource(Res.string.workout_finish_title)) },
            text = {
                val body = when {
                    empty -> stringResource(Res.string.workout_finish_body_empty)
                    unlogged == 0 && emptyExercises == 0 -> stringResource(Res.string.workout_finish_body_clean)
                    else -> listOfNotNull(
                        unlogged.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.workout_finish_unlogged, it, it) },
                        emptyExercises.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.workout_finish_empty_exercises, it, it) },
                    ).joinToString(" ")
                }
                Column {
                    Text(body, color = Tung.colors.muted)
                    if (past && !empty) {
                        Spacer(Modifier.height(16.dp))
                        DurationStepper(
                            durationMillis = lasted,
                            canLess = lasted - WORKOUT_TIME_STEP_MILLIS >= WORKOUT_TIME_STEP_MILLIS,
                            canMore = lasted + WORKOUT_TIME_STEP_MILLIS <= longest,
                            onLess = { duration = lasted - WORKOUT_TIME_STEP_MILLIS },
                            onMore = { duration = lasted + WORKOUT_TIME_STEP_MILLIS },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmFinish = false; vm.finish(if (past) state.startedAt + lasted else null) }) {
                    Text(stringResource(Res.string.workout_finish_confirm), color = Tung.colors.accentText)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmFinish = false }) {
                    Text(stringResource(Res.string.workout_keep_training), color = Tung.colors.text)
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
                Text(pluralStringResource(Res.plurals.workout_remove_exercise_title, logged, exerciseDisplayName(exercise.nameKey, exercise.name), logged))
            },
            text = { Text(stringResource(Res.string.workout_remove_exercise_body), color = Tung.colors.muted) },
            confirmButton = {
                TextButton(onClick = { confirmRemoveId = null; vm.removeExercise(exercise.workoutExerciseId) }) {
                    Text(stringResource(Res.string.workout_remove_exercise_confirm), color = Tung.colors.accentText)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveId = null }) {
                    Text(stringResource(Res.string.action_cancel), color = Tung.colors.text)
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
                actionLabel = askAssistant?.let { stringResource(Res.string.voice_ask_assistant) },
                onAction = { askAssistant?.invoke() },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MicButton(active = voice.listening || voice.handsFree, onTap = onMic, onLongPress = onMicLongPress)
            val text = when (sticky) {
                is StickyButton.LogSet -> {
                    val set = sticky.current.set
                    val reps = if (set.reps > 0) set.reps.toString() else stringResource(Res.string.workout_reps_empty)
                    val name = exerciseDisplayName(sticky.current.exercise.nameKey, sticky.current.exercise.name)
                    stringResource(Res.string.workout_log_set, name, localWeight(set.weightKg), reps)
                }
                is StickyButton.AddSet -> {
                    val name = exerciseDisplayName(sticky.exercise.nameKey, sticky.exercise.name)
                    val set = sticky.copyOf
                    if (set == null) {
                        stringResource(Res.string.workout_sticky_add_set_empty, name)
                    } else {
                        val reps = if (set.reps > 0) set.reps.toString() else stringResource(Res.string.workout_reps_empty)
                        stringResource(Res.string.workout_sticky_add_set, name, localWeight(set.weightKg), reps)
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
    val title = stringResource(Res.string.rest_title)
    val remaining = stringResource(Res.string.rest_remaining, left)
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
    val addDescription = stringResource(Res.string.rest_add_description)
    Row(
        modifier = Modifier.fillMaxWidth().background(Tung.colors.background).padding(start = 56.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(Res.string.rest_skip), style = MaterialTheme.typography.labelLarge, color = Tung.colors.text)
        }
        TextButton(
            onClick = onAdd,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = addDescription },
        ) {
            Text(stringResource(Res.string.rest_add), style = MaterialTheme.typography.labelLarge, color = Tung.colors.text)
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
                    stringResource(Res.string.workout_empty),
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
                    Res.string.workout_add_set_description,
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
                        stringResource(Res.string.workout_add_set),
                        style = MaterialTheme.typography.labelLarge,
                        color = Tung.colors.text,
                    )
                }
            }
        }
        item(key = ADD_EXERCISE_KEY) {
            val text = stringResource(Res.string.workout_add_exercise)
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
    val selectDescription = stringResource(Res.string.workout_select_exercise, name)
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
                painterResource(Res.drawable.ic_trending_up),
                contentDescription = stringResource(Res.string.workout_progress_for, name),
                tint = Tung.colors.muted,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(Res.string.workout_exercise_menu, name),
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
                    text = { Text(stringResource(Res.string.workout_rest_timer)) },
                    onClick = { menuOpen = false; onRestTimer() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.workout_remove_exercise)) },
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
    val deleteLabel = stringResource(Res.string.workout_delete_set)
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
            val description = stringResource(Res.string.workout_delete_set_number, set.number)
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
                        stringResource(Res.string.workout_delete),
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
    val reps = if (set.reps > 0) set.reps.toString() else stringResource(Res.string.workout_reps_empty)
    val setDescription = stringResource(Res.string.workout_set_number_description, set.number)
    val selectDescription = stringResource(Res.string.workout_select_set, set.number)
    val currentState = stringResource(Res.string.workout_current_set)
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
                    unit = stringResource(Res.string.workout_unit_kg),
                    description = stringResource(Res.string.workout_edit_weight, weight),
                    actions = deleteAction,
                    onClick = onEditWeight,
                )
                Text(stringResource(Res.string.times_sign), style = Tung.numbers.inline, color = Tung.colors.muted)
                NumberCell(
                    value = reps,
                    unit = stringResource(Res.string.workout_unit_reps),
                    description = stringResource(Res.string.workout_edit_reps, reps),
                    actions = deleteAction,
                    onClick = onEditReps,
                )
            }
            set.previous?.let { prev ->
                Text(
                    stringResource(Res.string.workout_previous, localWeight(prev.weightKg), prev.reps),
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
        if (completed) Res.string.workout_mark_undone else Res.string.workout_mark_done,
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
@Composable
private fun stepDescription(id: PluralStringResource, stepKg: Double): String {
    val quantity = if (stepKg % 1.0 == 0.0) stepKg.toInt() else 2
    return pluralStringResource(id, quantity, localWeight(stepKg))
}

@Composable
private fun weightSpec(initial: String): NumberInputSpec {
    return NumberInputSpec(
        title = stringResource(Res.string.input_weight_title),
        unit = stringResource(Res.string.workout_unit_kg),
        initialText = initial,
        keyboardType = KeyboardType.Decimal,
        fieldDescription = stringResource(Res.string.input_field_weight),
        minusDescription = { text ->
            stepDescription(Res.plurals.input_minus_weight, WEIGHT_RULER.stepBelow(parseWeight(text) ?: 0.0))
        },
        plusDescription = { text ->
            stepDescription(Res.plurals.input_plus_weight, WEIGHT_RULER.stepAt(parseWeight(text) ?: 0.0))
        },
        hint = stringResource(Res.string.input_hint),
        errorText = stringResource(Res.string.input_error_weight),
        doneText = stringResource(Res.string.input_done),
        minusText = stringResource(Res.string.input_minus),
        plusText = stringResource(Res.string.input_plus),
        isValid = { parseWeight(it) != null },
        step = { text, steps -> localWeight(stepWeight(parseWeight(text) ?: 0.0, steps)) },
        sanitize = { text -> text.filter { it.isDigit() || it == ',' || it == '.' }.take(6) },
        ruler = WEIGHT_RULER,
        toValue = ::parseWeight,
        fromValue = ::localWeight,
        rulerDescription = stringResource(Res.string.input_ruler_weight),
        rulerState = { stringResource(Res.string.input_ruler_weight_state, localWeight(it)) },
    )
}

