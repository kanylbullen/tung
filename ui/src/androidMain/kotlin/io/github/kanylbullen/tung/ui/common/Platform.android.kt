package io.github.kanylbullen.tung.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalView
import java.text.NumberFormat
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/** [ExerciseNames], in `data/`, over the running app's `ex_*` strings. */
@Composable
actual fun exerciseDisplayName(nameKey: String?, name: String): String {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    return remember(nameKey, name, configuration) { ExerciseNames.resolve(context, nameKey, name) }
}

@Composable
actual fun rememberTickHaptic(): () -> Unit {
    val view = LocalView.current
    return remember(view) { { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) } }
}

actual fun localPercent(fraction: Double): String = NumberFormat.getPercentInstance().format(fraction)

actual fun weekdayName(isoWeekday: Int, short: Boolean): String {
    val locale = java.util.Locale.getDefault()
    val style = if (short) java.time.format.TextStyle.SHORT else java.time.format.TextStyle.FULL
    return java.time.DayOfWeek.of(isoWeekday).getDisplayName(style, locale)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}
