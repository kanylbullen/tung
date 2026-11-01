package io.github.kanylbullen.tung.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.NSNumberFormatterPercentStyle
import platform.Foundation.autoupdatingCurrentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import kotlin.math.roundToLong
import platform.UIKit.UISelectionFeedbackGenerator

/**
 * The stored English name for now: the iOS app has no `ex_*` names yet. They
 * come with the iOS shell, which needs them in Compose Resources.
 */
@Composable
actual fun exerciseDisplayName(nameKey: String?, name: String): String = name

@Composable
actual fun rememberTickHaptic(): () -> Unit {
    val generator = remember { UISelectionFeedbackGenerator() }
    return remember(generator) { { generator.selectionChanged() } }
}

/** One formatter in the current locale, made once: they are costly to create, and safe to share. */
private val percentFormat: NSNumberFormatter by lazy {
    NSNumberFormatter().apply { numberStyle = NSNumberFormatterPercentStyle }
}

actual fun localPercent(fraction: Double): String =
    percentFormat.stringFromNumber(NSNumber(fraction)) ?: "${(fraction * 100).toInt()}%"

// The autoupdating locale follows a language change without a new formatter.
private val weekdayFormat: NSDateFormatter by lazy {
    NSDateFormatter().apply { locale = NSLocale.autoupdatingCurrentLocale }
}

actual fun weekdayName(isoWeekday: Int, style: WeekdayStyle, capitalized: Boolean): String {
    val symbols = when (style) {
        WeekdayStyle.NARROW -> weekdayFormat.veryShortStandaloneWeekdaySymbols
        WeekdayStyle.SHORT -> weekdayFormat.shortStandaloneWeekdaySymbols
        WeekdayStyle.FULL -> weekdayFormat.standaloneWeekdaySymbols
    }
    // Foundation's lists start on Sunday; ISO weekday 7 is Sunday.
    val name = symbols[isoWeekday % 7] as String
    return if (capitalized) name.replaceFirstChar { it.uppercase() } else name
}

private val volumeFormat: NSNumberFormatter by lazy {
    NSNumberFormatter().apply {
        locale = NSLocale.autoupdatingCurrentLocale
        numberStyle = NSNumberFormatterDecimalStyle
        maximumFractionDigits = 0u
    }
}

actual fun localVolume(kg: Double): String =
    volumeFormat.stringFromNumber(NSNumber(kg.roundToLong().toDouble())) ?: kg.roundToLong().toString()

internal actual fun dateText(epochMillis: Long, pattern: String, patternWithYear: String): String {
    val date = NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)
    val calendar = NSCalendar.currentCalendar
    val sameYear = calendar.component(NSCalendarUnitYear, fromDate = date) == calendar.component(NSCalendarUnitYear, fromDate = NSDate())
    val chosen = if (sameYear) pattern else patternWithYear
    val formatter = dateFormats.getOrPut(chosen) {
        NSDateFormatter().apply {
            locale = NSLocale.autoupdatingCurrentLocale
            dateFormat = chosen
        }
    }
    return formatter.stringFromDate(date).replaceFirstChar { it.uppercase() }
}

// One formatter per pattern: a list of workout rows asks for the same two on every recomposition.
private val dateFormats = mutableMapOf<String, NSDateFormatter>()
