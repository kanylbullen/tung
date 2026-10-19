package io.github.kanylbullen.tung.core

import kotlin.math.roundToInt

/**
 * The scale of the ruler picker in the number sheet: a tick every [step] from
 * 0 to [max], and a long, labelled tick every [majorEvery] ticks.
 *
 * Positions are measured in ticks from zero and may lie between ticks: a
 * typed 82.3 kg sits at 32.92. Only a drag snaps; typing never does.
 */
data class RulerScale(val step: Double, val max: Double, val majorEvery: Int) {

    init {
        require(step > 0.0) { "step must be positive" }
        require(max >= 0.0) { "max must not be negative" }
        require(majorEvery > 0) { "majorEvery must be positive" }
    }

    /** Index of the last tick; the first is 0. */
    val lastTick: Int = (max / step).roundToInt()

    /** Where [value] sits on the ruler, clamped to its ends. */
    fun positionOf(value: Double): Double = (value / step).coerceIn(0.0, lastTick.toDouble())

    /** The value under [position], clamped to the ruler and rounded to hundredths. */
    fun valueAt(position: Double): Double =
        roundToHundredths(position.coerceIn(0.0, lastTick.toDouble()) * step)

    /** The tick nearest [position]: where a released drag comes to rest. */
    fun snap(position: Double): Int = position.roundToInt().coerceIn(0, lastTick)

    /** [value] snapped to the nearest step and clamped to the ruler. */
    fun snapValue(value: Double): Double = valueAt(snap(positionOf(value)).toDouble())

    fun isMajor(tick: Int): Boolean = tick % majorEvery == 0
}

/** Weight: 2.5 kg steps up to 500 kg, labelled every 10 kg. */
val WEIGHT_RULER = RulerScale(step = WEIGHT_STEP_KG, max = 500.0, majorEvery = 4)

/** Reps: one rep per step up to 100, labelled every 5. */
val REPS_RULER = RulerScale(step = 1.0, max = 100.0, majorEvery = 5)
