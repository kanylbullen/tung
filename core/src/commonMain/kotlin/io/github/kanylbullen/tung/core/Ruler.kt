package io.github.kanylbullen.tung.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A stretch of the ruler starting at [from] and running to the next segment's
 * `from` (or the ruler's end): a tick every [step], and a long, labelled tick
 * on every multiple of [labelEvery].
 */
data class RulerSegment(val from: Double, val step: Double, val labelEvery: Double)

/**
 * The scale of the ruler picker in the number sheet, piecewise: each
 * [RulerSegment] has its own step, from 0 to [max].
 *
 * Positions are measured in ticks from zero, and every tick takes the same
 * width on screen whatever its step, so the mapping between position and value
 * is linear within a segment and bends at a boundary. Positions may lie
 * between ticks: a typed 82.3 kg sits between 82.5 and 80. Only a drag snaps;
 * typing never does.
 *
 * A tap on the ruler jumps to the nearest multiple of [jumpEvery].
 */
data class RulerScale(val segments: List<RulerSegment>, val max: Double, val jumpEvery: Double) {

    init {
        require(segments.isNotEmpty()) { "a ruler needs a segment" }
        require(segments.first().from == 0.0) { "the first segment starts at 0" }
        require(segments.all { it.step > 0.0 && it.labelEvery > 0.0 }) { "steps and labels must be positive" }
        require(segments.zipWithNext().all { (a, b) -> b.from > a.from }) { "segments must ascend" }
        require(max >= segments.last().from) { "max lies before the last segment" }
        require(jumpEvery > 0.0) { "jumpEvery must be positive" }
        val ends = segments.drop(1).map { it.from } + max
        segments.zip(ends).forEach { (segment, end) ->
            require(isWhole((end - segment.from) / segment.step)) { "a segment must end on its own grid" }
        }
    }

    /** The tick each segment starts on. */
    private val startTicks: List<Int> = segments.zipWithNext()
        .runningFold(0) { tick, (a, b) -> tick + ((b.from - a.from) / a.step).roundToInt() }

    /** Index of the last tick; the first is 0. */
    val lastTick: Int = startTicks.last() + ((max - segments.last().from) / segments.last().step).roundToInt()

    /** The step on the ruler at [value]: going up from it, and at a boundary the step above. */
    fun stepAt(value: Double): Double = segments[segmentOfValue(value)].step

    /** The step on the ruler just below [value]: at a boundary the step below it. */
    fun stepBelow(value: Double): Double =
        segments[segments.indexOfLast { it.from < value - EPSILON }.coerceAtLeast(0)].step

    /** Where [value] sits on the ruler, clamped to its ends. */
    fun positionOf(value: Double): Double = rawPosition(value).coerceIn(0.0, lastTick.toDouble())

    /** The value under [position], clamped to the ruler and rounded to hundredths. */
    fun valueAt(position: Double): Double = roundToHundredths(rawValue(position.coerceIn(0.0, lastTick.toDouble())))

    /** The tick nearest [position]: where a released drag comes to rest. */
    fun snapTick(position: Double): Int = position.roundToInt().coerceIn(0, lastTick)

    /** [value] moved to the nearest point on the grid and clamped to the ruler. */
    fun snap(value: Double): Double = valueAt(snapTick(positionOf(value)).toDouble())

    /**
     * One step up from [value]: the next point on the grid above it, so an
     * off-grid 31 goes to 32.5. Past [max] the last segment's step carries on,
     * since a typed weight may lie beyond the ruler; the caller clamps.
     */
    fun stepUp(value: Double): Double {
        val position = rawPosition(value)
        val nearest = position.roundToInt()
        val next = if (abs(position - nearest) < EPSILON) nearest + 1.0 else ceil(position)
        return roundToHundredths(rawValue(next))
    }

    /** One step down from [value]: the next point on the grid below it, never below 0. */
    fun stepDown(value: Double): Double {
        val position = rawPosition(value)
        val nearest = position.roundToInt()
        val next = if (abs(position - nearest) < EPSILON) nearest - 1.0 else floor(position)
        return roundToHundredths(rawValue(next.coerceAtLeast(0.0)))
    }

    /** Does [tick] get a long, labelled line? */
    fun isMajor(tick: Int): Boolean {
        val value = rawValue(tick.toDouble())
        return isWhole(value / segments[segmentOfValue(value)].labelEvery)
    }

    /**
     * Where a tap at [value] (the unsnapped value under the finger) jumps to:
     * the nearest multiple of [jumpEvery]. Exactly between two, the one nearer
     * [toward], the value the ruler shows now; and if that is a tie too, the
     * upper one.
     */
    fun jumpTarget(value: Double, toward: Double): Double {
        val tapped = value.coerceIn(0.0, max)
        val lower = floor(tapped / jumpEvery + EPSILON) * jumpEvery
        val upper = lower + jumpEvery
        val below = tapped - lower
        val above = upper - tapped
        val target = when {
            abs(below - above) > EPSILON -> if (below < above) lower else upper
            abs(toward - lower) < abs(toward - upper) - EPSILON -> lower
            else -> upper
        }
        return roundToHundredths(target.coerceIn(0.0, floor(max / jumpEvery + EPSILON) * jumpEvery))
    }

    private fun segmentOfValue(value: Double): Int =
        segments.indexOfLast { it.from <= value + EPSILON }.coerceAtLeast(0)

    private fun segmentOfPosition(position: Double): Int =
        startTicks.indexOfLast { it <= position + EPSILON }.coerceAtLeast(0)

    /** Position of [value] without clamping at [max]: the last segment runs on. */
    private fun rawPosition(value: Double): Double {
        val v = value.coerceAtLeast(0.0)
        val i = segmentOfValue(v)
        return startTicks[i] + (v - segments[i].from) / segments[i].step
    }

    private fun rawValue(position: Double): Double {
        val p = position.coerceAtLeast(0.0)
        val i = segmentOfPosition(p)
        return segments[i].from + (p - startTicks[i]) * segments[i].step
    }

    private companion object {
        const val EPSILON = 1e-6

        fun isWhole(x: Double): Boolean = abs(x - x.roundToInt()) < EPSILON
    }
}

/**
 * Weight: 1 kg steps up to 30 kg, labelled every 5, then 2.5 kg steps up to
 * 500 kg, labelled every 10. A tap jumps to the nearest 10 kg.
 */
val WEIGHT_RULER = RulerScale(
    segments = listOf(
        RulerSegment(from = 0.0, step = 1.0, labelEvery = 5.0),
        RulerSegment(from = 30.0, step = 2.5, labelEvery = 10.0),
    ),
    max = 500.0,
    jumpEvery = 10.0,
)

/** Reps: one rep per step up to 100, labelled every 5. A tap jumps to the nearest 5. */
val REPS_RULER = RulerScale(
    segments = listOf(RulerSegment(from = 0.0, step = 1.0, labelEvery = 5.0)),
    max = 100.0,
    jumpEvery = 5.0,
)
