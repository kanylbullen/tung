package io.github.kanylbullen.tung.web.ui

import androidx.compose.runtime.Composable
import io.github.kanylbullen.tung.core.Muscle
import io.github.kanylbullen.tung.core.muscles.BodyMap
import io.github.kanylbullen.tung.core.muscles.BodyPoint
import io.github.kanylbullen.tung.core.muscles.BodySide

/**
 * The phone's muscle map: core's [BodyMap] drawn as SVG, the front and the
 * back figure side by side. [shade] is a muscle's opacity of the accent, 0
 * to 1; null leaves it the muted surface tone. One node for a screen
 * reader, named [summary].
 */
@Composable
fun MuscleMap(summary: String, shade: (Muscle) -> Double?) {
    val height = 200.0
    val width = height * BodyMap.ASPECT
    val gap = 16.0
    fun polygon(points: List<BodyPoint>, dx: Double) =
        points.joinToString(" ") { "${dx + it.x * width},${it.y * height}" }
    val svg = buildString {
        append("<svg viewBox=\"0 0 ${2 * width + gap} $height\" aria-hidden=\"true\">")
        listOf(BodySide.FRONT to 0.0, BodySide.BACK to width + gap).forEach { (side, dx) ->
            BodyMap.outlines[side]?.let { append("<polygon class=\"body\" points=\"${polygon(it, dx)}\"/>") }
            BodyMap.regionsOn(side).forEach { region ->
                val opacity = shade(region.muscle)
                if (opacity == null || opacity <= 0.0) {
                    append("<polygon class=\"rest\" points=\"${polygon(region.points, dx)}\"/>")
                } else {
                    append("<polygon class=\"worked\" fill-opacity=\"${opacity.coerceIn(0.0, 1.0)}\" points=\"${polygon(region.points, dx)}\"/>")
                }
            }
        }
        append("</svg>")
    }
    SvgBox(svg, summary, "muscle-map")
}
