package io.github.kanylbullen.tung.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import io.github.kanylbullen.tung.ui.resources.Res
import io.github.kanylbullen.tung.ui.resources.archivo_variable
import org.jetbrains.compose.resources.Font

@OptIn(ExperimentalTextApi::class)
@Composable
private fun archivo(weight: FontWeight) = Font(
    resource = Res.font.archivo_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/**
 * One family — Archivo, variable weight — for everything. Read from Compose
 * Resources, so it is composable; [TungTheme] builds the typography from it.
 */
@Composable
fun archivoFamily(): FontFamily = FontFamily(
    archivo(FontWeight.Normal),
    archivo(FontWeight.Medium),
    archivo(FontWeight.SemiBold),
    archivo(FontWeight.Bold),
    archivo(FontWeight.ExtraBold),
)

/**
 * The same file at its widest and heaviest, for the few places that carry the
 * app's voice: the wordmark on Home and the hero number. Everything else stays
 * [archivoFamily] at the normal width.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun archivoWideFamily(): FontFamily = FontFamily(
    Font(
        resource = Res.font.archivo_variable,
        weight = FontWeight.Black,
        variationSettings = FontVariation.Settings(FontVariation.weight(900), FontVariation.width(118f)),
    ),
)

private const val TABULAR = "tnum"

private val base = Typography()

/** Material's type scale in [archivo]. */
fun tungTypography(archivo: FontFamily): Typography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = archivo),
    displayMedium = base.displayMedium.copy(fontFamily = archivo),
    displaySmall = base.displaySmall.copy(fontFamily = archivo),
    headlineLarge = base.headlineLarge.copy(fontFamily = archivo, fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontFamily = archivo, fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontFamily = archivo, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = archivo, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = archivo, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontFamily = archivo, fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.copy(fontFamily = archivo),
    bodyMedium = base.bodyMedium.copy(fontFamily = archivo),
    bodySmall = base.bodySmall.copy(fontFamily = archivo),
    labelLarge = base.labelLarge.copy(fontFamily = archivo, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    labelMedium = base.labelMedium.copy(fontFamily = archivo),
    labelSmall = base.labelSmall.copy(fontFamily = archivo),
)

/** The numbers are the content: big, heavy, tabular so columns don't jitter. */
@Immutable
data class NumberStyles(
    val huge: TextStyle,
    val set: TextStyle,
    val stat: TextStyle,
    val inline: TextStyle,
    /** The hero number on Home (the week's volume): [archivoWideFamily], tight, tabular. */
    val hero: TextStyle,
    /** "TUNG" on Home: [archivoWideFamily], small, spaced. */
    val wordmark: TextStyle,
)

/** The number styles in [archivo], with [wide] for the hero number and the wordmark. */
fun tungNumbers(archivo: FontFamily, wide: FontFamily): NumberStyles = NumberStyles(
    hero = TextStyle(
        fontFamily = wide,
        fontWeight = FontWeight.Black,
        fontSize = 64.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.02).em,
        fontFeatureSettings = TABULAR,
    ),
    wordmark = TextStyle(
        fontFamily = wide,
        fontWeight = FontWeight.Black,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.04.em,
    ),
    huge = TextStyle(
        fontFamily = archivo,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 96.sp,
        lineHeight = 104.sp,
        fontFeatureSettings = TABULAR,
    ),
    set = TextStyle(
        fontFamily = archivo,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontFeatureSettings = TABULAR,
    ),
    stat = TextStyle(
        fontFamily = archivo,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        fontFeatureSettings = TABULAR,
    ),
    inline = TextStyle(
        fontFamily = archivo,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = TABULAR,
    ),
)

/** Outside [TungTheme] (a preview, a test) the numbers fall back to the platform font. */
val LocalNumberStyles = staticCompositionLocalOf { tungNumbers(FontFamily.Default, FontFamily.Default) }
