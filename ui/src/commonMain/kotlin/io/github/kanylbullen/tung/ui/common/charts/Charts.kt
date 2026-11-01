package io.github.kanylbullen.tung.ui.common.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kanylbullen.tung.ui.theme.Tung

/**
 * The charts of the Progress screens, drawn with Compose [Canvas]: one accent
 * for the data, muted tabular labels, at most [MAX_Y_LABELS] on the y axis.
 * Each chart is a single semantics node whose content description is
 * [summary], a sentence that says what the chart shows, so it is readable by
 * a screen reader and by the flow tests.
 */

/** One point of a line chart: [x] in any linear unit (the epoch day), [y] the value. */
data class ChartPoint(val x: Long, val y: Double)

@Composable
private fun labelStyle(): TextStyle = Tung.numbers.inline.copy(fontSize = 12.sp, lineHeight = 14.sp, color = Tung.colors.muted)

/**
 * A line with a dot per point. [xLabel] names a point on the x axis (only the
 * first and the last are written); [yLabel] writes a tick value.
 */
@Composable
fun LineChart(
    points: List<ChartPoint>,
    summary: String,
    xLabel: (Long) -> String,
    yLabel: (Double) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
) {
    val measurer = rememberTextMeasurer()
    val style = labelStyle()
    val accent = Tung.colors.accent
    val grid = Tung.colors.border
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { contentDescription = summary },
    ) {
        if (points.isEmpty()) return@Canvas
        val ticks = niceTicks(points.minOf { it.y }, points.maxOf { it.y })
        val plot = plotArea(measurer, style, ticks.map(yLabel), xLabels = true)
        val lo = ticks.first()
        val hi = ticks.last()
        drawYAxis(measurer, style, ticks, yLabel, plot, grid)
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        fun px(x: Long): Float =
            if (maxX == minX) plot.left + plot.width / 2 else plot.left + plot.width * (x - minX).toFloat() / (maxX - minX)
        fun py(y: Double): Float =
            if (hi == lo) plot.top + plot.height / 2 else plot.bottom - plot.height * ((y - lo) / (hi - lo)).toFloat()
        val path = Path()
        points.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(px(p.x), py(p.y)) else path.lineTo(px(p.x), py(p.y))
        }
        drawPath(path, accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        points.forEachIndexed { i, p ->
            val r = if (i == points.lastIndex) 4.5.dp.toPx() else 3.dp.toPx()
            drawCircle(accent, r, Offset(px(p.x), py(p.y)))
        }
        drawXLabels(measurer, style, xLabel(minX), if (maxX != minX) xLabel(maxX) else null, plot)
    }
}

/**
 * One bar per value, oldest left; a value of 0 is a short baseline mark so
 * an empty week still has its place. [firstLabel] and [lastLabel] go under the
 * first and the last bar.
 */
@Composable
fun BarChart(
    values: List<Double>,
    summary: String,
    firstLabel: String,
    lastLabel: String,
    yLabel: (Double) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
) {
    val measurer = rememberTextMeasurer()
    val style = labelStyle()
    val accent = Tung.colors.accent
    val grid = Tung.colors.border
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { contentDescription = summary },
    ) {
        if (values.isEmpty()) return@Canvas
        val max = values.max()
        val ticks = if (max <= 0.0) listOf(0.0) else niceTicks(0.0, max)
        val plot = plotArea(measurer, style, ticks.map(yLabel), xLabels = true)
        val hi = ticks.last()
        drawYAxis(measurer, style, ticks, yLabel, plot, grid)
        val slot = plot.width / values.size
        val barWidth = slot * 0.6f
        values.forEachIndexed { i, v ->
            val left = plot.left + slot * i + (slot - barWidth) / 2
            if (v <= 0.0 || hi <= 0.0) {
                drawRect(grid, Offset(left, plot.bottom - 2.dp.toPx()), Size(barWidth, 2.dp.toPx()))
            } else {
                val h = plot.height * (v / hi).toFloat()
                drawRect(accent, Offset(left, plot.bottom - h), Size(barWidth, h))
            }
        }
        drawXLabels(measurer, style, firstLabel, lastLabel, plot)
    }
}

/** A small line without axes, for a list row; [summary] is its description. */
@Composable
fun Sparkline(values: List<Double>, summary: String, modifier: Modifier = Modifier) {
    val accent = Tung.colors.accent
    Canvas(modifier.size(width = 72.dp, height = 28.dp).clearAndSetSemantics { contentDescription = summary }) {
        if (values.isEmpty()) return@Canvas
        if (values.size == 1) {
            // One session in the span: the dot alone, where the line would end.
            drawCircle(accent, 3.dp.toPx(), Offset(size.width - 3.dp.toPx(), size.height / 2))
            return@Canvas
        }
        val lo = values.min()
        val hi = values.max()
        val inset = 3.dp.toPx()
        val w = size.width - 2 * inset
        val h = size.height - 2 * inset
        fun point(i: Int): Offset {
            val y = if (hi == lo) 0.5f else ((values[i] - lo) / (hi - lo)).toFloat()
            return Offset(inset + w * i / values.lastIndex, inset + h * (1 - y))
        }
        val path = Path()
        values.indices.forEach { i -> point(i).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
        drawPath(path, accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(accent, 3.dp.toPx(), point(values.lastIndex))
    }
}

private data class Plot(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** The area inside the labels: y labels on the left, x labels below, half a label's height free on top. */
private fun DrawScope.plotArea(measurer: TextMeasurer, style: TextStyle, yLabels: List<String>, xLabels: Boolean): Plot {
    val labelWidth = yLabels.maxOfOrNull { measurer.measure(it, style).size.width } ?: 0
    val labelHeight = measurer.measure("0", style).size.height
    return Plot(
        left = labelWidth + 8.dp.toPx(),
        top = labelHeight / 2f,
        right = size.width - 4.dp.toPx(),
        bottom = size.height - if (xLabels) labelHeight + 6.dp.toPx() else labelHeight / 2f,
    )
}

private fun DrawScope.drawYAxis(
    measurer: TextMeasurer,
    style: TextStyle,
    ticks: List<Double>,
    yLabel: (Double) -> String,
    plot: Plot,
    grid: Color,
) {
    val lo = ticks.first()
    val hi = ticks.last()
    ticks.forEach { t ->
        val y = if (hi == lo) plot.top + plot.height / 2 else plot.bottom - plot.height * ((t - lo) / (hi - lo)).toFloat()
        drawLine(grid, Offset(plot.left, y), Offset(plot.right, y), strokeWidth = 1.dp.toPx())
        val text = measurer.measure(yLabel(t), style)
        drawText(text, topLeft = Offset(plot.left - 8.dp.toPx() - text.size.width, y - text.size.height / 2f))
    }
}

private fun DrawScope.drawXLabels(measurer: TextMeasurer, style: TextStyle, first: String, last: String?, plot: Plot) {
    val y = plot.bottom + 6.dp.toPx()
    val start = measurer.measure(first, style)
    drawText(start, topLeft = Offset(plot.left, y))
    if (last != null) {
        val end = measurer.measure(last, style)
        drawText(end, topLeft = Offset(plot.right - end.size.width, y))
    }
}
