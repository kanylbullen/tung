package io.github.kanylbullen.tung.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import io.github.kanylbullen.tung.core.progress.TrendPoint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import org.jetbrains.compose.web.dom.Div

// The phone's charts (ui/common/charts), drawn as SVG: one accent for the
// data, the border colour for grid lines, labels muted, no chart library.
// Each chart is one node with its summary as the accessible name.

internal fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

/** At most four "nice" y values (steps of 1, 2, 2.5 or 5 × 10ⁿ) covering [min]..[max], the phone's `niceTicks`. */
fun niceTicks(min: Double, max: Double, count: Int = 4): List<Double> {
    if (max <= min) return listOf(min)
    val raw = (max - min) / (count - 1)
    val magnitude = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { it >= raw }
    val start = floor(min / step) * step
    val end = ceil(max / step) * step
    val ticks = ArrayList<Double>()
    var t = start
    while (t <= end + step / 2) {
        ticks += t
        t += step
    }
    return ticks
}

/** Puts [svg] markup into the page as one image named [summary]. */
@Composable
internal fun SvgBox(svg: String, summary: String, className: String) {
    key(svg) {
        Div({
            classes("chart", className)
            attr("role", "img")
            attr("aria-label", summary)
            ref { element ->
                element.innerHTML = svg
                onDispose { }
            }
        })
    }
}

/**
 * A line with a dot per point (the last one larger), x proportional to the
 * date, up to four y labels and the first and last date under the axis.
 */
@Composable
fun LineChart(points: List<TrendPoint>, summary: String, yLabel: (Double) -> String, dateLabel: (TrendPoint) -> String) {
    val width = 360.0
    val height = 200.0
    val left = 44.0
    val right = 12.0
    val top = 12.0
    val bottom = 28.0
    val values = points.map { it.value }
    val ticks = niceTicks(values.min(), values.max())
    val lo = ticks.first()
    val hi = ticks.last().takeIf { it > lo } ?: (lo + 1)
    val days = points.map { it.date.toEpochDays().toDouble() }
    val d0 = days.first()
    val d1 = days.last().takeIf { it > d0 } ?: (d0 + 1)
    fun x(i: Int) = left + (days[i] - d0) / (d1 - d0) * (width - left - right)
    fun y(v: Double) = top + (1 - (v - lo) / (hi - lo)) * (height - top - bottom)
    val svg = buildString {
        append("<svg viewBox=\"0 0 $width $height\" width=\"100%\" preserveAspectRatio=\"none\" aria-hidden=\"true\">")
        ticks.forEach { t ->
            append("<line x1=\"$left\" x2=\"${width - right}\" y1=\"${y(t)}\" y2=\"${y(t)}\" class=\"grid\"/>")
            append("<text x=\"${left - 6}\" y=\"${y(t) + 4}\" text-anchor=\"end\" class=\"axis\">${esc(yLabel(t))}</text>")
        }
        append("<polyline fill=\"none\" class=\"line\" points=\"")
        points.indices.forEach { i -> append("${x(i)},${y(values[i])} ") }
        append("\"/>")
        points.indices.forEach { i ->
            val r = if (i == points.lastIndex) 5 else 3
            append("<circle cx=\"${x(i)}\" cy=\"${y(values[i])}\" r=\"$r\" class=\"dot\"/>")
        }
        append("<text x=\"$left\" y=\"${height - 6}\" class=\"axis\">${esc(dateLabel(points.first()))}</text>")
        append("<text x=\"${width - right}\" y=\"${height - 6}\" text-anchor=\"end\" class=\"axis\">${esc(dateLabel(points.last()))}</text>")
        append("</svg>")
    }
    SvgBox(svg, summary, "line-chart")
}

/** Bars from zero, one per value; a zero is a short mark on the baseline. */
@Composable
fun BarChart(values: List<Double>, summary: String) {
    val width = 360.0
    val height = 96.0
    val gap = 4.0
    val bar = (width - gap * (values.size - 1)) / values.size.coerceAtLeast(1)
    val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val svg = buildString {
        append("<svg viewBox=\"0 0 $width $height\" width=\"100%\" preserveAspectRatio=\"none\" aria-hidden=\"true\">")
        values.forEachIndexed { i, v ->
            val h = if (v > 0) (v / max * (height - 4)).coerceAtLeast(3.0) else 2.0
            val cls = if (v > 0) "bar" else "bar empty"
            append("<rect x=\"${i * (bar + gap)}\" y=\"${height - h}\" width=\"$bar\" height=\"$h\" rx=\"2\" class=\"$cls\"/>")
        }
        append("</svg>")
    }
    SvgBox(svg, summary, "bar-chart")
}

/** A line without axes for a row; one value is a dot. */
@Composable
fun Sparkline(values: List<Double>, summary: String) {
    val width = 96.0
    val height = 32.0
    val svg = buildString {
        append("<svg viewBox=\"0 0 $width $height\" width=\"$width\" height=\"$height\" aria-hidden=\"true\">")
        if (values.size == 1) {
            append("<circle cx=\"${width / 2}\" cy=\"${height / 2}\" r=\"3\" class=\"dot\"/>")
        } else if (values.size > 1) {
            val lo = values.min()
            val hi = values.max().takeIf { abs(it - lo) > 1e-9 } ?: (lo + 1)
            append("<polyline fill=\"none\" class=\"line\" points=\"")
            values.forEachIndexed { i, v ->
                append("${3 + i * (width - 6) / (values.size - 1)},${3 + (1 - (v - lo) / (hi - lo)) * (height - 6)} ")
            }
            append("\"/>")
        }
        append("</svg>")
    }
    SvgBox(svg, summary, "sparkline")
}
