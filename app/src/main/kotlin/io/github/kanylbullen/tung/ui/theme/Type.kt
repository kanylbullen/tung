package io.github.kanylbullen.tung.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.kanylbullen.tung.R

@OptIn(ExperimentalTextApi::class)
private fun archivo(weight: FontWeight) = Font(
    resId = R.font.archivo_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** One family — Archivo, variable weight — for everything. */
val Archivo = FontFamily(
    archivo(FontWeight.Normal),
    archivo(FontWeight.Medium),
    archivo(FontWeight.SemiBold),
    archivo(FontWeight.Bold),
    archivo(FontWeight.ExtraBold),
)

private const val TABULAR = "tnum"

private val base = Typography()

val TungTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Archivo),
    displayMedium = base.displayMedium.copy(fontFamily = Archivo),
    displaySmall = base.displaySmall.copy(fontFamily = Archivo),
    headlineLarge = base.headlineLarge.copy(fontFamily = Archivo, fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontFamily = Archivo, fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontFamily = Archivo, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = Archivo, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = Archivo, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontFamily = Archivo, fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.copy(fontFamily = Archivo),
    bodyMedium = base.bodyMedium.copy(fontFamily = Archivo),
    bodySmall = base.bodySmall.copy(fontFamily = Archivo),
    labelLarge = base.labelLarge.copy(fontFamily = Archivo, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    labelMedium = base.labelMedium.copy(fontFamily = Archivo),
    labelSmall = base.labelSmall.copy(fontFamily = Archivo),
)

/** The numbers are the content: big, heavy, tabular so columns don't jitter. */
@Immutable
data class NumberStyles(
    val huge: TextStyle,
    val set: TextStyle,
    val stat: TextStyle,
    val inline: TextStyle,
)

val TungNumbers = NumberStyles(
    huge = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 96.sp,
        lineHeight = 104.sp,
        fontFeatureSettings = TABULAR,
    ),
    set = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontFeatureSettings = TABULAR,
    ),
    stat = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        fontFeatureSettings = TABULAR,
    ),
    inline = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = TABULAR,
    ),
)

val LocalNumberStyles = staticCompositionLocalOf { TungNumbers }
