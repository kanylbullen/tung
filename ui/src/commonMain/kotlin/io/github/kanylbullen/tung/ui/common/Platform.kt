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

/**
 * The name of ISO weekday [isoWeekday] (1 = Monday) in the device's language,
 * capitalised: "Mon" or "Monday" ([short] or not).
 */
expect fun weekdayName(isoWeekday: Int, short: Boolean): String
