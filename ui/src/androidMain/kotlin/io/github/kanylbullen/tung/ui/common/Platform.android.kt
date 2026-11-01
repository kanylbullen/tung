package io.github.kanylbullen.tung.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalView
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToLong
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

actual fun weekdayName(isoWeekday: Int, style: WeekdayStyle, capitalized: Boolean): String {
    val locale = Locale.getDefault()
    val textStyle = when (style) {
        WeekdayStyle.NARROW -> TextStyle.NARROW
        WeekdayStyle.SHORT -> TextStyle.SHORT
        WeekdayStyle.FULL -> TextStyle.FULL
    }
    val name = DayOfWeek.of(isoWeekday).getDisplayName(textStyle, locale)
    return if (capitalized) name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() } else name
}

actual fun localVolume(kg: Double): String = NumberFormat.getIntegerInstance().format(kg.roundToLong())

internal actual fun dateText(epochMillis: Long, pattern: String, patternWithYear: String): String {
    val locale = Locale.getDefault()
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val chosen = if (date.year == ZonedDateTime.now(zone).year) pattern else patternWithYear
    val text = DateTimeFormatter.ofPattern(chosen, locale).format(date)
    return text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}
