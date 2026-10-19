package io.github.kanylbullen.tung.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Contrast ratios (WCAG 2.x) are noted where a pair is used for text.

// Dark — kall grafit and krita.
val Graphite = Color(0xFF1A1D21)
val GraphiteRaised = Color(0xFF23272D)
val GraphiteBorder = Color(0xFF2F343B)
val Chalk = Color(0xFFECE7DD)          // on Graphite 13.8:1, on Raised 12.2:1
val ChalkMuted = Color(0xFF9AA0A6)     // on Graphite 6.4:1, on Raised 5.7:1
val PlateYellow = Color(0xFFF2C230)    // on Graphite 10.1:1; Graphite on it 10.1:1

// Light.
val Paper = Color(0xFFF6F6F4)
val PaperRaised = Color(0xFFFFFFFF)
val PaperBorder = Color(0xFFE2E1DC)
val Ink = Color(0xFF1A1D21)            // on Paper 15.6:1
val InkMuted = Color(0xFF5F6368)       // on Paper 5.6:1, on white 6.0:1
val PlateYellowDeep = Color(0xFFB98900) // fill only: Ink on it 5.4:1, vs white 3.2:1 (ok for shapes, not text)
val PlateYellowText = Color(0xFF8A6500) // accent as text on Paper 4.9:1, on white 5.3:1

/** Tokens Material's scheme has no good slot for. */
@Immutable
data class TungColors(
    val background: Color,
    val raised: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    /** Fill: primary button, ticked set. */
    val accent: Color,
    /** Content drawn on [accent]. */
    val onAccent: Color,
    /** Accent used as text on [background] — darker than [accent] in light mode for contrast. */
    val accentText: Color,
)

val DarkTungColors = TungColors(
    background = Graphite,
    raised = GraphiteRaised,
    border = GraphiteBorder,
    text = Chalk,
    muted = ChalkMuted,
    accent = PlateYellow,
    onAccent = Graphite,
    accentText = PlateYellow,
)

val LightTungColors = TungColors(
    background = Paper,
    raised = PaperRaised,
    border = PaperBorder,
    text = Ink,
    muted = InkMuted,
    accent = PlateYellowDeep,
    onAccent = Ink,
    accentText = PlateYellowText,
)

val LocalTungColors = staticCompositionLocalOf { DarkTungColors }
