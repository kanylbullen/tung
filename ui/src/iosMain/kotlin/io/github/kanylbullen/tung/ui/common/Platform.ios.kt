package io.github.kanylbullen.tung.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterPercentStyle
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
