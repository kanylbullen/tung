package io.github.kanylbullen.tung.ui.exercises

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.kanylbullen.tung.ui.resources.*
import io.github.kanylbullen.tung.core.Muscle
import io.github.kanylbullen.tung.core.muscles.BodyMap
import io.github.kanylbullen.tung.core.muscles.BodyPoint
import io.github.kanylbullen.tung.core.muscles.BodySide
import io.github.kanylbullen.tung.ui.common.muscleLabel
import io.github.kanylbullen.tung.ui.theme.Tung

/** Secondary muscles are the accent at this alpha. */
private const val SECONDARY_ALPHA = 0.4f

/** Untrained regions: the muted text colour this faint over the silhouette, so the body reads without competing. */
private const val REST_ALPHA = 0.22f

/**
 * Front and back of a body, side by side, with [primary] muscles in the
 * accent and [secondary] ones in the accent at 40 %; everything else is a
 * muted surface tone. The shapes are core's `BodyMap` (normalised polygons),
 * scaled to [height]; each figure is `BodyMap.ASPECT` as wide as it is high.
 * Under it, one quiet line: Primary / Secondary. For a screen reader the map
 * is one node that names the muscles.
 */
@Composable
fun MuscleMap(primary: Set<Muscle>, secondary: Set<Muscle>, modifier: Modifier = Modifier, height: Dp = 160.dp) {
    val accent = Tung.colors.accent
    val faint = accent.copy(alpha = SECONDARY_ALPHA)
    val description = mapDescription(primary, secondary - primary)

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BodyFigures(height, description) { muscle ->
            when (muscle) {
                in primary -> 1f
                in secondary -> SECONDARY_ALPHA
                else -> 0f
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(accent, stringResource(Res.string.exercise_map_primary))
            if ((secondary - primary).isNotEmpty()) Legend(faint, stringResource(Res.string.exercise_map_secondary))
        }
    }
}

/** The least worked muscle of a heat map is the accent at this alpha; the most worked is the full accent. */
private const val HEAT_MIN_ALPHA = 0.3f

/**
 * The body as [MuscleMap] draws it, each muscle in the accent by how much it
 * was worked: [intensity] is 0 for an untrained muscle (the muted surface
 * tone) and 1 for the most worked one, the rest in between from
 * [HEAT_MIN_ALPHA]. [description] is what a screen reader says for the map.
 */
@Composable
fun MuscleHeatMap(intensity: (Muscle) -> Double, description: String, modifier: Modifier = Modifier, height: Dp = 160.dp) {
    Box(modifier, contentAlignment = Alignment.Center) {
        BodyFigures(height, description) { muscle ->
            val i = intensity(muscle).toFloat().coerceIn(0f, 1f)
            if (i <= 0f) 0f else HEAT_MIN_ALPHA + (1f - HEAT_MIN_ALPHA) * i
        }
    }
}

/**
 * Both figures, one semantics node described by [description]. [shade] is
 * each muscle's accent alpha, 0 for one drawn in the untrained tone; regions
 * are painted from the faintest up, so a trained region is never hidden
 * under a less trained neighbour.
 */
@Composable
private fun BodyFigures(height: Dp, description: String, shade: (Muscle) -> Float) {
    val colors = Tung.colors
    val silhouette = colors.border
    val rest = colors.muted.copy(alpha = REST_ALPHA)
    val accent = colors.accent
    Canvas(
        Modifier
            .height(height)
            .width(height * BodyMap.ASPECT * 2 + height * GAP)
            .semantics { contentDescription = description },
    ) {
        val figureWidth = size.height * BodyMap.ASPECT
        val gap = size.height * GAP
        BodySide.entries.forEachIndexed { i, side ->
            val left = i * (figureWidth + gap)
            drawPolygon(BodyMap.outlines.getValue(side), left, figureWidth, silhouette)
            BodyMap.regionsOn(side)
                .map { it to shade(it.muscle) }
                .sortedBy { it.second }
                .forEach { (region, alpha) ->
                    drawPolygon(region.points, left, figureWidth, if (alpha <= 0f) rest else accent.copy(alpha = alpha))
                }
        }
    }
}

/** The space between the two figures, as a fraction of the height. */
private const val GAP = 0.12f

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = Tung.colors.muted)
    }
}

@Composable
private fun mapDescription(primary: Set<Muscle>, secondary: Set<Muscle>): String {
    val primaryNames = primary.map { muscleLabel(it) }.joinToString(", ")
    return if (secondary.isEmpty()) {
        stringResource(Res.string.exercise_map_description_primary, primaryNames)
    } else {
        stringResource(Res.string.exercise_map_description, primaryNames, secondary.map { muscleLabel(it) }.joinToString(", "))
    }
}

/** One closed polygon of normalised [points], in the figure whose box starts at [left] and is [width] wide and the canvas high. */
private fun DrawScope.drawPolygon(points: List<BodyPoint>, left: Float, width: Float, color: Color) {
    if (points.size < 3) return
    val path = Path()
    points.forEachIndexed { i, p ->
        val x = left + p.x * width
        val y = p.y * size.height
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}
