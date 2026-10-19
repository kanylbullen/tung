package io.github.kanylbullen.tung.core

import kotlin.math.roundToLong

const val WEIGHT_STEP_KG = 2.5
const val MAX_WEIGHT_KG = 999.0
const val MAX_REPS = 999

/**
 * Parses a weight typed by a person: both "82,5" and "82.5" mean 82.5.
 * Blank, negative, non-numeric and absurdly large input give null.
 */
fun parseWeight(input: String): Double? {
    val text = input.trim().replace(',', '.')
    if (text.isEmpty()) return null
    val value = text.toDoubleOrNull() ?: return null
    if (value.isNaN() || value.isInfinite() || value < 0.0 || value > MAX_WEIGHT_KG) return null
    return roundToHundredths(value)
}

/** Whole, non-negative reps. "8.5" is not a rep count. */
fun parseReps(input: String): Int? {
    val text = input.trim()
    if (text.isEmpty()) return null
    val value = text.toIntOrNull() ?: return null
    return value.takeIf { it in 0..MAX_REPS }
}

/** One −/+ press on the weight, never below zero. */
fun stepWeight(current: Double, steps: Int): Double =
    roundToHundredths((current + steps * WEIGHT_STEP_KG).coerceIn(0.0, MAX_WEIGHT_KG))

fun stepReps(current: Int, steps: Int): Int = (current + steps).coerceIn(0, MAX_REPS)

/** 80.0 → "80", 82.5 → "82,5" (with a Swedish separator), 1.25 → "1,25". */
fun formatWeight(kg: Double, decimalSeparator: Char = ','): String {
    val hundredths = (kg * 100).roundToLong()
    val whole = hundredths / 100
    val fraction = hundredths % 100
    return when {
        fraction == 0L -> whole.toString()
        fraction % 10 == 0L -> "$whole$decimalSeparator${fraction / 10}"
        else -> "$whole$decimalSeparator${fraction.toString().padStart(2, '0')}"
    }
}

private fun roundToHundredths(value: Double): Double = (value * 100).roundToLong() / 100.0
