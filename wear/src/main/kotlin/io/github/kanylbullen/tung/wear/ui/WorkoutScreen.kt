package io.github.kanylbullen.tung.wear.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.HierarchicalFocusCoordinator
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.HorizontalPageIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PageIndicatorState
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.dialog.Alert
import androidx.wear.compose.material.dialog.Dialog
import io.github.kanylbullen.tung.core.RestTimer
import io.github.kanylbullen.tung.core.wear.WatchExercise
import io.github.kanylbullen.tung.core.wear.WatchSet
import io.github.kanylbullen.tung.core.wear.WatchWorkoutState
import io.github.kanylbullen.tung.wear.AdjustField
import io.github.kanylbullen.tung.wear.R
import io.github.kanylbullen.tung.wear.WatchLogButton
import io.github.kanylbullen.tung.wear.focusExerciseIndex
import io.github.kanylbullen.tung.wear.focusSet
import io.github.kanylbullen.tung.wear.logButton
import io.github.kanylbullen.tung.wear.lastTimeFor
import kotlinx.coroutines.delay

/**
 * The workout in progress: one page per exercise in a [HorizontalPager]
 * (Compose Foundation's, the stable one; wear-compose 1.4 has no pager of its
 * own), and a last page to finish. While a rest runs, its countdown takes the
 * whole screen. The screen stays on, as on the phone.
 *
 * [HierarchicalFocusCoordinator] (experimental in wear-compose 1.4) gives the
 * rotary input to the page on screen only, so the bezel scrolls that page's list.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalWearFoundationApi::class)
@Composable
fun WorkoutScreen(
    state: WatchWorkoutState,
    phoneUnreachable: Boolean,
    onLogSet: (String) -> Unit,
    onAddSet: (String) -> Unit,
    onSelectSet: (String) -> Unit,
    onAdjust: (AdjustField, String) -> Unit,
    onFinish: () -> Unit,
    onSkipRest: () -> Unit,
    onAddRest: () -> Unit,
    claimRestBuzz: (endsAtMillis: Long) -> Boolean,
) {
    KeepScreenOn()
    val context = LocalContext.current
    val rest = state.rest?.toRestTimer()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(rest) {
        if (rest == null) return@LaunchedEffect
        var sawItRunning = false
        while (true) {
            val t = System.currentTimeMillis()
            now = t
            if (!rest.isRunningAt(t)) break
            sawItRunning = true
            delay(rest.remainingAt(t).coerceAtMost(250L))
        }
        // Buzz once per rest, and only for one the watch watched run out (not one that ended while it was away).
        if (sawItRunning && claimRestBuzz(rest.endsAtMillis)) buzz(context)
    }

    if (rest != null && rest.isRunningAt(now)) {
        RestScreen(rest, now, onSkipRest, onAddRest)
        return
    }

    val pageCount = state.exercises.size.coerceAtLeast(1) + 1
    val pagerState = rememberPagerState(initialPage = state.focusExerciseIndex() ?: 0) { pageCount }
    LaunchedEffect(state.currentSetId, state.addSetWorkoutExerciseId) {
        val index = state.focusExerciseIndex() ?: return@LaunchedEffect
        if (index != pagerState.currentPage) pagerState.animateScrollToPage(index)
    }

    Scaffold(timeText = { TimeText() }) {
        Box(Modifier.fillMaxSize()) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                HierarchicalFocusCoordinator(requiresFocus = { page == pagerState.currentPage }) {
                    val exercise = state.exercises.getOrNull(page)
                    when {
                        exercise != null -> ExercisePage(
                            exercise = exercise,
                            currentSetId = state.currentSetId,
                            button = state.logButton(exercise),
                            phoneUnreachable = phoneUnreachable,
                            onLogSet = onLogSet,
                            onAddSet = onAddSet,
                            onSelectSet = onSelectSet,
                            onAdjust = onAdjust,
                        )
                        page == pageCount - 1 -> FinishPage(phoneUnreachable, onFinish)
                        else -> CenteredNote(stringResource(R.string.workout_empty))
                    }
                }
            }
            HorizontalPageIndicator(
                pageIndicatorState = pagerState.asIndicatorState(),
                selectedColor = TungColors.text,
                unselectedColor = TungColors.border,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun PagerState.asIndicatorState() = object : PageIndicatorState {
    override val pageOffset: Float get() = currentPageOffsetFraction
    override val selectedPage: Int get() = currentPage
    override val pageCount: Int get() = this@asIndicatorState.pageCount
}

@Composable
private fun ExercisePage(
    exercise: WatchExercise,
    currentSetId: String?,
    button: WatchLogButton?,
    phoneUnreachable: Boolean,
    onLogSet: (String) -> Unit,
    onAddSet: (String) -> Unit,
    onSelectSet: (String) -> Unit,
    onAdjust: (AdjustField, String) -> Unit,
) {
    val focus = exercise.focusSet(currentSetId)
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 1)
    ScalingLazyColumn(
        state = listState,
        autoCentering = AutoCenteringParams(itemIndex = 1),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(
                text = exercise.name,
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // A tap on the name makes this exercise the current one, as the header does on the phone.
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = focus != null && focus.setId != currentSetId) { focus?.let { onSelectSet(it.setId) } },
            )
        }
        if (focus == null) {
            item { Text(stringResource(R.string.all_logged), color = TungColors.muted, textAlign = TextAlign.Center) }
            // Every set of the workout is ticked: logging added none, so the button offers another (issue #64).
            if (button is WatchLogButton.AddSet) {
                item {
                    Chip(
                        label = { Text(stringResource(R.string.add_set), maxLines = 1) },
                        onClick = { onAddSet(button.workoutExerciseId) },
                        colors = accentChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            item { BigSet(focus, onAdjust) }
            exercise.lastTimeFor(focus)?.let { last ->
                item {
                    Text(
                        text = stringResource(R.string.previous, localWeight(last.weightKg), last.reps),
                        color = TungColors.muted,
                        style = MaterialTheme.typography.caption1,
                    )
                }
            }
            item {
                Chip(
                    label = { Text(stringResource(R.string.log_set), maxLines = 1) },
                    // With 0 reps there is nothing to log yet: ask for the reps first, as the phone does.
                    onClick = { if (focus.reps <= 0) onAdjust(AdjustField.Reps, focus.setId) else onLogSet(focus.setId) },
                    colors = accentChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (phoneUnreachable) item { UnreachableNote() }
        items(exercise.sets, key = { it.setId }) { set ->
            SetRow(
                number = exercise.sets.indexOf(set) + 1,
                set = set,
                isFocus = set.setId == focus?.setId,
                onSelect = { onSelectSet(set.setId) },
            )
        }
    }
}

/** "80 × 8", each number a target that opens the adjust screen. */
@Composable
private fun BigSet(set: WatchSet, onAdjust: (AdjustField, String) -> Unit) {
    val weight = localWeight(set.weightKg)
    val reps = if (set.reps > 0) set.reps.toString() else stringResource(R.string.reps_empty)
    val weightDescription = stringResource(R.string.edit_weight, weight)
    val repsDescription = stringResource(R.string.edit_reps, reps)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text(
            text = weight,
            style = numberStyle(34.sp),
            modifier = Modifier
                .clickable { onAdjust(AdjustField.Weight, set.setId) }
                .semantics { contentDescription = weightDescription }
                .padding(4.dp),
        )
        Text(stringResource(R.string.times), style = numberStyle(22.sp), color = TungColors.muted)
        Text(
            text = reps,
            style = numberStyle(34.sp),
            modifier = Modifier
                .clickable { onAdjust(AdjustField.Reps, set.setId) }
                .semantics { contentDescription = repsDescription }
                .padding(4.dp),
        )
    }
}

/** A set in the small list: muted when logged, accent when it is the one above, tap to make it current. */
@Composable
private fun SetRow(number: Int, set: WatchSet, isFocus: Boolean, onSelect: () -> Unit) {
    val color = when {
        set.completed -> TungColors.muted
        isFocus -> TungColors.accent
        else -> TungColors.text
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .clickable(enabled = !set.completed && !isFocus, onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(number.toString(), style = numberStyle(16.sp), color = TungColors.muted)
        Text(
            text = "${localWeight(set.weightKg)}${stringResource(R.string.times)}" +
                if (set.reps > 0) set.reps.toString() else stringResource(R.string.reps_empty),
            style = numberStyle(16.sp),
            color = color,
        )
    }
}

@Composable
private fun FinishPage(phoneUnreachable: Boolean, onFinish: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (phoneUnreachable) UnreachableNote()
        Chip(
            label = { Text(stringResource(R.string.finish_workout), color = TungColors.accent) },
            onClick = { confirming = true },
            colors = raisedChipColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Dialog(showDialog = confirming, onDismissRequest = { confirming = false }) {
        Alert(
            title = { Text(stringResource(R.string.finish_title), textAlign = TextAlign.Center) },
            message = {
                Text(stringResource(R.string.finish_body), textAlign = TextAlign.Center, style = MaterialTheme.typography.body2)
            },
        ) {
            item {
                Chip(
                    label = { Text(stringResource(R.string.finish_confirm)) },
                    onClick = {
                        confirming = false
                        onFinish()
                    },
                    colors = accentChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Chip(
                    label = { Text(stringResource(R.string.keep_training)) },
                    onClick = { confirming = false },
                    colors = raisedChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The rest countdown: a ring that empties, the time left, Skip and +30 s. */
@Composable
private fun RestScreen(rest: RestTimer, now: Long, onSkip: () -> Unit, onAdd: () -> Unit) {
    val remaining = rest.remainingAt(now)
    val progress = if (rest.durationMillis > 0) remaining.toFloat() / rest.durationMillis else 0f
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxSize().padding(4.dp),
            indicatorColor = TungColors.accent,
            trackColor = TungColors.border,
            strokeWidth = 6.dp,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.rest_title), color = TungColors.muted)
            Text(countdown(remaining), style = numberStyle(44.sp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactChip(
                    label = { Text(stringResource(R.string.rest_skip)) },
                    onClick = onSkip,
                    colors = raisedChipColors(),
                )
                val addDescription = stringResource(R.string.rest_add_description)
                CompactChip(
                    label = { Text(stringResource(R.string.rest_add)) },
                    onClick = onAdd,
                    colors = raisedChipColors(),
                    modifier = Modifier.semantics { contentDescription = addDescription },
                )
            }
        }
    }
}

@Composable
private fun CenteredNote(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = TungColors.muted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** Two short pulses when a rest is over. The phone buzzes too; fine for v0. */
private fun buzz(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
    vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
}
