package io.github.kanylbullen.tung.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import io.github.kanylbullen.tung.core.ROUTINE_NAME_SEPARATOR
import io.github.kanylbullen.tung.ui.theme.Tung
import kotlin.math.ceil

/**
 * A routine's name on one line, with its group muted: the import names
 * routines "<package> · <day>" ("Skokloster Gym · JT2 dag 1"), so everything
 * up to the last [ROUTINE_NAME_SEPARATOR] is drawn in the muted colour at
 * normal weight and the day stands out in [style]'s own weight. When the
 * line is too narrow it is the muted part that is cut ("Your Adap… · Full
 * Body Workout"), never the day: the group is the same on row after row,
 * the day is what tells them apart ([routineNameWidths] decides). A name
 * without the separator is plain text, cut at its end. Whatever is drawn,
 * the whole name is one semantics node, so a screen reader hears it entire
 * and a test finds it as one string.
 */
@Composable
fun RoutineName(name: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.titleMedium) {
    val cut = name.lastIndexOf(ROUTINE_NAME_SEPARATOR)
    val prefix = if (cut > 0) name.substring(0, cut) else ""
    val suffix = if (cut > 0) name.substring(cut + ROUTINE_NAME_SEPARATOR.length) else name
    if (prefix.isBlank() || suffix.isBlank()) {
        Text(name, modifier = modifier, style = style, color = Tung.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    // A few letters and the ellipsis, in the style's own size so a larger font scale keeps them.
    val minPrefix = with(LocalDensity.current) {
        if (style.fontSize.isSp) (style.fontSize * MIN_PREFIX_EMS).roundToPx() else MIN_PREFIX_WIDTH.roundToPx()
    }
    // What the prefix drew, read in measure: a cut line rarely fills the width it was given.
    val prefixLayout = remember { arrayOfNulls<TextLayoutResult>(1) }
    Layout(
        content = {
            Text(
                prefix,
                style = style,
                color = Tung.colors.muted,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { prefixLayout[0] = it },
            )
            Text(ROUTINE_NAME_SEPARATOR, style = style, color = Tung.colors.muted, fontWeight = FontWeight.Normal, maxLines = 1, softWrap = false)
            Text(suffix, style = style, color = Tung.colors.text, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        },
        modifier = modifier.clearAndSetSemantics { text = AnnotatedString(name) },
    ) { measurables, constraints ->
        val widths = routineNameWidths(
            available = constraints.maxWidth,
            prefixWanted = measurables[0].maxIntrinsicWidth(constraints.maxHeight),
            separatorWanted = measurables[1].maxIntrinsicWidth(constraints.maxHeight),
            suffixWanted = measurables[2].maxIntrinsicWidth(constraints.maxHeight),
            minPrefix = minPrefix,
        )
        val maxHeight = constraints.maxHeight
        // A part given no width is neither measured nor placed, so nothing of it is drawn.
        val prefixPlaceable = if (widths.prefix > 0) measurables[0].measure(Constraints(maxWidth = widths.prefix, maxHeight = maxHeight)) else null
        val separatorPlaceable = if (widths.separator > 0) measurables[1].measure(Constraints(maxWidth = widths.separator, maxHeight = maxHeight)) else null
        val suffixPlaceable = measurables[2].measure(Constraints(maxWidth = widths.suffix, maxHeight = maxHeight))
        // The separator follows the prefix's last glyph, not the width the prefix was given. The
        // layout result is used only when it is the one this measure produced (the same width).
        val prefixDrawn = prefixPlaceable?.let { placeable ->
            prefixLayout[0]?.takeIf { it.size.width == placeable.width }
                ?.let { ceil(it.getLineRight(0)).toInt().coerceIn(0, placeable.width) }
                ?: placeable.width
        } ?: 0
        val separatorWidth = separatorPlaceable?.width ?: 0
        // On one baseline, whatever the weights' metrics.
        val parts = listOfNotNull(prefixPlaceable, separatorPlaceable, suffixPlaceable)
        val baseline = parts.maxOf { it.baseline() }
        val width = constraints.constrainWidth(prefixDrawn + separatorWidth + suffixPlaceable.width)
        val height = constraints.constrainHeight(parts.maxOf { baseline - it.baseline() + it.height })
        layout(width, height) {
            prefixPlaceable?.let { it.placeRelative(0, baseline - it.baseline()) }
            separatorPlaceable?.let { it.placeRelative(prefixDrawn, baseline - it.baseline()) }
            suffixPlaceable.placeRelative(prefixDrawn + separatorWidth, baseline - suffixPlaceable.baseline())
        }
    }
}

/** What each part of a routine name gets of the line, in pixels; a part with 0 is left out. */
data class RoutineNameWidths(val prefix: Int, val separator: Int, val suffix: Int)

/**
 * Shares [available] pixels between a routine name's muted prefix, the
 * separator and its suffix, which want [prefixWanted], [separatorWanted] and
 * [suffixWanted] to be whole. Everything when it all fits; else the suffix
 * whole and the prefix what is left, as long as that is at least
 * [minPrefix] (enough for a few letters and an ellipsis); else the suffix
 * alone, cut to the line, with no prefix and no separator, since a leading
 * " · " would say nothing. The suffix is never the part that goes.
 */
fun routineNameWidths(available: Int, prefixWanted: Int, separatorWanted: Int, suffixWanted: Int, minPrefix: Int): RoutineNameWidths {
    if (available - suffixWanted - separatorWanted >= prefixWanted) return RoutineNameWidths(prefixWanted, separatorWanted, suffixWanted)
    val room = available - suffixWanted - separatorWanted
    return if (room >= minPrefix) RoutineNameWidths(room, separatorWanted, suffixWanted)
    else RoutineNameWidths(0, 0, minOf(suffixWanted, available))
}

/** A placeable's first baseline, or its top when it has none. */
private fun Placeable.baseline(): Int = this[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: 0

/** The least a cut prefix gets, in ems of the style: a few letters and the ellipsis, so "Yo… · " never degrades to "… · ". */
private const val MIN_PREFIX_EMS = 3f

/** The same minimum for a style without a font size. */
private val MIN_PREFIX_WIDTH = 48.dp
