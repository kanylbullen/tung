package io.github.kanylbullen.tung.ui.common

import androidx.compose.runtime.Composable

/**
 * An exercise's displayed name: the catalogue name in the app's language for
 * a catalogue exercise ([nameKey]), else [name]. On Android the generated
 * `ex_*` string resources; recomposes with the configuration, so a language
 * change shows at once.
 */
@Composable
expect fun exerciseDisplayName(nameKey: String?, name: String): String

/**
 * One light tick per step, for the ruler: Android's `CLOCK_TICK` through the
 * view, a selection change on iOS.
 */
@Composable
expect fun rememberTickHaptic(): () -> Unit

/** How long a weekday's name is: "M", "Mon" or "Monday". */
enum class WeekdayStyle { NARROW, SHORT, FULL }

/** The name of ISO weekday [isoWeekday] (1 = Monday) in the device's language, capitalised. */
expect fun weekdayName(isoWeekday: Int, style: WeekdayStyle): String

/** Whole kilos with the locale's grouping: 12 450. */
expect fun localVolume(kg: Double): String

/**
 * [epochMillis] in the device's zone and language by the date pattern
 * [pattern] ("EEE d MMM"), or by [patternWithYear] when it is not this year,
 * first letter capitalised. The patterns are the ones `java.time` and
 * `NSDateFormatter` read alike.
 */
internal expect fun dateText(epochMillis: Long, pattern: String, patternWithYear: String): String
