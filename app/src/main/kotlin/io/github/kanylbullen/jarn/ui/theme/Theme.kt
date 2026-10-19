package io.github.kanylbullen.jarn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp

/** One radius for buttons, sheets and dialogs. List rows are square. */
val JarnCorner = RoundedCornerShape(12.dp)

private val JarnShapes = Shapes(
    extraSmall = JarnCorner,
    small = JarnCorner,
    medium = JarnCorner,
    large = JarnCorner,
    extraLarge = JarnCorner,
)

/** Maps our tokens onto every Material slot so no default purple leaks through. */
private fun schemeFrom(c: JarnColors, dark: Boolean): ColorScheme {
    val start = if (dark) darkColorScheme() else lightColorScheme()
    return start.copy(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = c.raised,
        onPrimaryContainer = c.text,
        inversePrimary = c.accent,
        secondary = c.text,
        onSecondary = c.background,
        secondaryContainer = c.border,
        onSecondaryContainer = c.text,
        tertiary = c.accentText,
        onTertiary = c.background,
        tertiaryContainer = c.raised,
        onTertiaryContainer = c.text,
        background = c.background,
        onBackground = c.text,
        surface = c.background,
        onSurface = c.text,
        surfaceVariant = c.raised,
        onSurfaceVariant = c.muted,
        surfaceTint = c.background,
        inverseSurface = c.text,
        inverseOnSurface = c.background,
        outline = c.border,
        outlineVariant = c.border,
        scrim = start.scrim,
        surfaceBright = c.raised,
        surfaceDim = c.background,
        surfaceContainerLowest = c.background,
        surfaceContainerLow = c.raised,
        surfaceContainer = c.raised,
        surfaceContainerHigh = c.raised,
        surfaceContainerHighest = c.raised,
    )
}

@Composable
fun JarnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Dynamic colour is deliberately off: the palette is part of the product.
    val colors = if (darkTheme) DarkJarnColors else LightJarnColors
    CompositionLocalProvider(
        LocalJarnColors provides colors,
        LocalNumberStyles provides JarnNumbers,
    ) {
        MaterialTheme(
            colorScheme = schemeFrom(colors, darkTheme),
            typography = JarnTypography,
            shapes = JarnShapes,
            content = content,
        )
    }
}

object Jarn {
    val colors: JarnColors
        @Composable @ReadOnlyComposable get() = LocalJarnColors.current
    val numbers: NumberStyles
        @Composable @ReadOnlyComposable get() = LocalNumberStyles.current
}
