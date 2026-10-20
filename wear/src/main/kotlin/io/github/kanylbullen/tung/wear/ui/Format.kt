package io.github.kanylbullen.tung.wear.ui

import io.github.kanylbullen.tung.core.formatWeight
import java.text.DecimalFormatSymbols
import kotlin.math.ceil

/** 82.5 → "82,5" in Swedish, "82.5" in English. */
fun localWeight(kg: Double): String = formatWeight(kg, DecimalFormatSymbols.getInstance().decimalSeparator)

/** A rest countdown: 83 500 ms → "1:24" (rounded up, so it reads 0:00 only at the end). */
fun countdown(millis: Long): String {
    val seconds = ceil(millis / 1000.0).toLong().coerceAtLeast(0L)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
