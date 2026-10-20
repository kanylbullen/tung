package io.github.kanylbullen.tung.wear

import io.github.kanylbullen.tung.core.MAX_REPS
import io.github.kanylbullen.tung.core.MAX_WEIGHT_KG
import io.github.kanylbullen.tung.core.WEIGHT_RULER
import io.github.kanylbullen.tung.core.stepReps
import io.github.kanylbullen.tung.core.stepWeight
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** What the adjust screen edits. */
enum class AdjustField {
    Weight,
    Reps;

    /**
     * [steps] presses of −/+ (or bezel clicks) from [value]: the weight
     * ruler's local step for weight (1 kg below 30, 2.5 kg from 30 up), one
     * rep for reps; kept within the phone's limits.
     */
    fun step(value: Double, steps: Int): Double = when (this) {
        Weight -> stepWeight(value, steps)
        Reps -> stepReps(value.roundToInt(), steps).toDouble()
    }

    /** The step a press of − would take from [value], for its screen-reader label. */
    fun stepBelow(value: Double): Double = when (this) {
        Weight -> WEIGHT_RULER.stepBelow(value)
        Reps -> 1.0
    }

    /** The step a press of + would take from [value]. */
    fun stepAbove(value: Double): Double = when (this) {
        Weight -> WEIGHT_RULER.stepAt(value)
        Reps -> 1.0
    }

    fun canStepDown(value: Double): Boolean = value > 0.0

    fun canStepUp(value: Double): Boolean = when (this) {
        Weight -> value < MAX_WEIGHT_KG
        Reps -> value < MAX_REPS
    }
}

/**
 * Turns rotary scroll events (bezel or crown, in pixels) into whole steps.
 *
 * A Galaxy Watch bezel sends one large event per click; a crown sends a stream
 * of small ones. So an event of at least [thresholdPx] is one step on its own,
 * and smaller events add up until they reach it. Turning the other way drops
 * whatever was gathered. Clockwise (positive) steps up.
 */
class RotaryStepper(private val thresholdPx: Float = DEFAULT_THRESHOLD_PX) {
    private var gathered = 0f

    /** The steps (−1, 0 or +1) that [pixels] of scroll make. */
    fun feed(pixels: Float): Int {
        if (pixels == 0f || pixels.isNaN()) return 0
        if (abs(pixels) >= thresholdPx) {
            gathered = 0f
            return pixels.sign.toInt()
        }
        if (gathered != 0f && gathered.sign != pixels.sign) gathered = 0f
        gathered += pixels
        if (abs(gathered) < thresholdPx) return 0
        val step = gathered.sign
        gathered -= step * thresholdPx
        return step.toInt()
    }

    companion object {
        const val DEFAULT_THRESHOLD_PX = 40f
    }
}
