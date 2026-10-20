package io.github.kanylbullen.tung.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Typography
import io.github.kanylbullen.tung.wear.R

/** The phone's dark tokens (app/.../ui/theme/Color.kt). A watch face is dark, so there is no light theme. */
object TungColors {
    val background = Color(0xFF1A1D21)
    val raised = Color(0xFF23272D)
    val border = Color(0xFF2F343B)
    val text = Color(0xFFECE7DD)
    val muted = Color(0xFF9AA0A6)
    val accent = Color(0xFFF2C230)
}

@OptIn(ExperimentalTextApi::class)
private fun archivo(weight: FontWeight) = Font(
    resId = R.font.archivo_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** One family, as on the phone. */
val Archivo = FontFamily(
    archivo(FontWeight.Normal),
    archivo(FontWeight.Medium),
    archivo(FontWeight.SemiBold),
    archivo(FontWeight.Bold),
)

/** The numbers: bold, tabular. */
fun numberStyle(size: TextUnit) = TextStyle(
    fontFamily = Archivo,
    fontWeight = FontWeight.Bold,
    fontSize = size,
    fontFeatureSettings = "tnum",
)

private val colors = Colors(
    primary = TungColors.accent,
    primaryVariant = TungColors.accent,
    secondary = TungColors.accent,
    secondaryVariant = TungColors.accent,
    background = TungColors.background,
    surface = TungColors.raised,
    onPrimary = TungColors.background,
    onSecondary = TungColors.background,
    onBackground = TungColors.text,
    onSurface = TungColors.text,
    onSurfaceVariant = TungColors.muted,
)

@Composable
fun TungWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(colors = colors, typography = Typography(defaultFontFamily = Archivo), content = content)
}

/** The primary action: plate yellow with graphite text. */
@Composable
fun accentChipColors() = ChipDefaults.chipColors(
    backgroundColor = TungColors.accent,
    contentColor = TungColors.background,
    secondaryContentColor = TungColors.background,
    iconColor = TungColors.background,
)

/** Everything else: the raised surface with chalk text. */
@Composable
fun raisedChipColors() = ChipDefaults.chipColors(
    backgroundColor = TungColors.raised,
    contentColor = TungColors.text,
    secondaryContentColor = TungColors.muted,
    iconColor = TungColors.text,
)
