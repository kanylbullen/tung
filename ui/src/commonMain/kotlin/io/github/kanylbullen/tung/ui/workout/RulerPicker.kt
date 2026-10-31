package io.github.kanylbullen.tung.ui.workout

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.kanylbullen.tung.core.RulerScale
import io.github.kanylbullen.tung.ui.common.rememberTickHaptic
import io.github.kanylbullen.tung.ui.theme.Tung
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

private val TickSpacing = 14.dp
private val RulerHeight = 72.dp

/**
 * A horizontal ruler under the big number. Drag it and the value under the
 * accent line follows; release and it snaps to the nearest tick; fling it to
 * travel far. One haptic tick per step. Tap it (press and lift without
 * moving) and it jumps to [RulerScale.jumpTarget] for the value under the
 * finger: the nearest 10 kg, or 5 reps.
 *
 * Built on [draggable] (horizontal only, so vertical swipes still reach the
 * sheet) and a plain float position animated with [animate], rather than a
 * `LazyRow`: the position is in ticks and every tick has the same width, so
 * a typed 82.3 sits exactly between two ticks instead of being forced onto the
 * grid, and one Canvas draws only the ticks in view. The scale is piecewise
 * (1 kg ticks below 30 kg, 2.5 kg above), which only [RulerScale] needs to know.
 *
 * [value] is the sheet's current number (null while the text is not one).
 * When it changes from outside — typing, −/+ — the ruler moves to it and says
 * nothing back: the ruler never rewrites what was typed. Only a drag or a tap
 * calls [onValueChange], always with a value on the grid.
 */
@Composable
fun RulerPicker(
    scale: RulerScale,
    value: Double?,
    onValueChange: (Double) -> Unit,
    tickLabel: (Double) -> String,
    description: String,
    stateDescriptionOf: @Composable (Double) -> String,
    modifier: Modifier = Modifier,
) {
    val hapticTick = rememberTickHaptic()
    val spacingPx = with(LocalDensity.current) { TickSpacing.toPx() }
    val scope = rememberCoroutineScope()
    val lastTick = scale.lastTick.toFloat()
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    // Position in ticks from zero; between ticks while moving or for an off-grid typed value.
    var position by remember(scale) { mutableFloatStateOf(scale.positionOf(value ?: 0.0).toFloat()) }
    val holder = remember(scale) { RulerMotion(scale.snapTick(position.toDouble())) }

    fun emit(newValue: Double) {
        holder.lastEmitted = newValue
        currentOnValueChange(newValue)
    }

    /** After a move the person caused: tick and report when the nearest step changes. */
    fun moved() {
        val tick = scale.snapTick(position.toDouble())
        if (tick != holder.tick) {
            holder.tick = tick
            hapticTick()
            emit(scale.valueAt(tick.toDouble()))
        }
    }

    fun settle(velocityPx: Float) {
        val velocity = -velocityPx / spacingPx // ticks per second
        val target = scale.snapTick(Decay.calculateTargetValue(position, velocity).toDouble())
        val generation = ++holder.generation
        holder.job = scope.launch {
            try {
                animate(
                    initialValue = position,
                    targetValue = target.toFloat(),
                    initialVelocity = velocity,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
                ) { x, _ ->
                    position = x.coerceIn(0f, lastTick)
                    moved()
                }
                // A short drag from an off-grid typed value can come back to the same tick.
                val snapped = scale.valueAt(target.toDouble())
                if (currentValue != snapped) emit(snapped)
            } finally {
                if (holder.generation == generation) holder.driving = false
            }
        }
    }

    /** A tap at [x] pixels from the ruler's left edge, [width] wide: jump to the nearest ten (or five). */
    fun jump(x: Float, width: Float) {
        val now = scale.valueAt(position.toDouble())
        val tapped = scale.valueAt((position + (x - width / 2f) / spacingPx).toDouble())
        val target = scale.jumpTarget(tapped, toward = now)
        val targetPosition = scale.positionOf(target).toFloat()
        holder.generation++
        holder.job?.cancel()
        holder.driving = false
        holder.tick = scale.snapTick(targetPosition.toDouble())
        if (currentValue != target) {
            hapticTick()
            emit(target)
        }
        if (abs(targetPosition - position) < 1e-4f) return
        holder.job = scope.launch {
            animate(position, targetPosition, animationSpec = tween(220, easing = FastOutSlowInEasing)) { p, _ ->
                position = p
            }
        }
    }

    // Typing or −/+ moved the number: follow it, unless it is our own echo.
    LaunchedEffect(scale, value) {
        val typed = value ?: return@LaunchedEffect
        if (holder.driving || typed == holder.lastEmitted) {
            holder.lastEmitted = null
            return@LaunchedEffect
        }
        holder.lastEmitted = null
        val target = scale.positionOf(typed).toFloat()
        holder.tick = scale.snapTick(target.toDouble())
        if (abs(target - position) < 1e-4f) return@LaunchedEffect
        holder.job?.cancel()
        holder.job = scope.launch {
            animate(position, target, animationSpec = tween(220, easing = FastOutSlowInEasing)) { x, _ ->
                position = x
            }
        }
    }

    val shown by remember(scale) { derivedStateOf { scale.valueAt(position.toDouble()) } }
    val state = stateDescriptionOf(shown)
    val muted = Tung.colors.muted
    val accent = Tung.colors.accent
    val labelStyle = Tung.numbers.inline.copy(color = muted)
    val measurer = rememberTextMeasurer(cacheSize = 24)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(RulerHeight)
            .pointerInput(scale) {
                // A tap is a press that lifts without travelling past the touch slop in any
                // direction. Nothing is consumed, so the drag below and the sheet's own
                // vertical drag see every event; a drag that starts never ends in a tap.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    var isTap = true
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            isTap = false
                            break
                        }
                        if ((change.position - down.position).getDistance() > slop) isTap = false
                        if (!change.pressed) {
                            if (change.isConsumed) isTap = false
                            break
                        }
                    }
                    if (isTap) jump(down.position.x, size.width.toFloat())
                }
            }
            .draggable(
                state = rememberDraggableState { delta ->
                    position = (position - delta / spacingPx).coerceIn(0f, lastTick)
                    moved()
                },
                orientation = Orientation.Horizontal,
                onDragStarted = {
                    holder.generation++
                    holder.job?.cancel()
                    holder.driving = true
                },
                onDragStopped = { velocity -> settle(velocity) },
            )
            .semantics {
                contentDescription = description
                stateDescription = state
            },
    ) {
        val centre = size.width / 2f
        val top = 6.dp.toPx()
        val minor = 14.dp.toPx()
        val major = 26.dp.toPx()
        val halfSpan = centre / spacingPx
        val first = floor(position - halfSpan).toInt().coerceAtLeast(0)
        val last = ceil(position + halfSpan).toInt().coerceAtMost(scale.lastTick)
        for (tick in first..last) {
            val x = centre + (tick - position) * spacingPx
            val isMajor = scale.isMajor(tick)
            drawLine(
                color = muted,
                start = Offset(x, top),
                end = Offset(x, top + if (isMajor) major else minor),
                strokeWidth = if (isMajor) 2.dp.toPx() else 1.dp.toPx(),
                cap = StrokeCap.Round,
            )
            if (isMajor) {
                val text = measurer.measure(tickLabel(scale.valueAt(tick.toDouble())), labelStyle)
                drawText(text, topLeft = Offset(x - text.size.width / 2f, top + major + 4.dp.toPx()))
            }
        }
        drawLine(
            color = accent,
            start = Offset(centre, 0f),
            end = Offset(centre, top + major + 2.dp.toPx()),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** Fling friction, in ticks: a quick flick travels a few dozen steps. */
private val Decay = exponentialDecay<Float>()

/** Bookkeeping for the ruler's motion; none of it is drawn, so none of it is state. */
private class RulerMotion(var tick: Int) {
    var job: Job? = null
    /** A drag or its fling is under way; outside changes to the value are ignored. */
    var driving = false
    /** Bumped by every drag start, so an interrupted fling does not end the next one. */
    var generation = 0
    /** The last value this ruler reported, so its echo through the text field is not followed. */
    var lastEmitted: Double? = null
}
