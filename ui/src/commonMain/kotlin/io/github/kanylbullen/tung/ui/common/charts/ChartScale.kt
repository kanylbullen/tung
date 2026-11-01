package io.github.kanylbullen.tung.ui.common.charts

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** At most this many labels on a chart's y axis. */
const val MAX_Y_LABELS = 4

/**
 * Round tick values covering [min]..[max], at most [maxTicks] of them, on a
 * step of 1, 2, 2.5 or 5 times a power of ten. The first and last tick are the
 * axis's ends. A flat series (min == max) gets one tick at its value.
 */
fun niceTicks(min: Double, max: Double, maxTicks: Int = MAX_Y_LABELS): List<Double> {
    require(maxTicks >= 2)
    if (!min.isFinite() || !max.isFinite()) return emptyList()
    if (max <= min) return listOf(min)
    val raw = (max - min) / (maxTicks - 1)
    val magnitude = 10.0.pow(floor(log10(raw)))
    for (multiple in listOf(1.0, 2.0, 2.5, 5.0, 10.0, 20.0)) {
        val step = multiple * magnitude
        val first = floor(min / step)
        val last = ceil(max / step)
        val count = (last - first).toInt() + 1
        if (count <= maxTicks) return (0 until count).map { (first + it) * step }
    }
    return listOf(min, max)
}
