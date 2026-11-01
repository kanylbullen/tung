package io.github.kanylbullen.tung.wear.ui

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The geometry of a round screen, in plain numbers (dp or px, as long as all
 * arguments use the same unit), so it can be tested without a screen. The
 * layouts use [chipTopInset] for the list padding; the round-screen tests use
 * [fitsInCircle] to check that a control lies on the visible circle.
 */
object RoundGeometry {

    /** Each side of a list keeps this share of the screen's width clear: Wear's standard inset. */
    const val LIST_SIDE_FRACTION = 0.052f

    /** Body text in a list keeps this much more on each side, so a block of text at the top or bottom of a list stays on the circle. */
    const val TEXT_SIDE_FRACTION = 0.07f

    /** A Wear chip's height (ChipDefaults.Height). */
    const val CHIP_HEIGHT = 52f

    /**
     * True if the rounded rectangle [left]..[right] × [top]..[bottom] with
     * corners of [cornerRadius] lies inside the circle of [diameter] centred
     * in a [diameter]-square screen, at least [margin] from its edge.
     * A rounded rectangle is inside a circle exactly when the centres of its
     * four corner arcs are within (radius − margin − cornerRadius) of the
     * circle's centre: 0 for a plain box, half the height for a chip.
     */
    fun fitsInCircle(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        diameter: Float,
        cornerRadius: Float = 0f,
        margin: Float = 0f,
    ): Boolean {
        val r = diameter / 2f
        val corner = cornerRadius.coerceIn(0f, minOf(right - left, bottom - top) / 2f)
        val allowed = r - margin - corner
        if (allowed < 0f) return false
        val xs = floatArrayOf(left + corner - r, right - corner - r)
        val ys = floatArrayOf(top + corner - r, bottom - corner - r)
        return xs.all { x -> ys.all { y -> hypot(x, y) <= allowed } }
    }

    /**
     * How far from the top of a round screen of [diameter] a chip of
     * [chipWidth] × [chipHeight] (rounded ends) must start for its ends to
     * touch the circle and not cross it: the top padding of a list whose first
     * item is a full-width chip (the same calculation as Horologist's
     * responsive padding). The bottom is symmetric.
     */
    fun chipTopInset(diameter: Float, chipWidth: Float, chipHeight: Float = CHIP_HEIGHT): Float {
        val r = diameter / 2f
        val endRadius = chipHeight / 2f
        // The centre of an end's arc may be (r − endRadius) from the screen's centre.
        val x = max(0f, chipWidth / 2f - endRadius)
        val reach = r - endRadius
        if (x >= reach) return r - endRadius
        val y = sqrt(reach * reach - x * x)
        return max(0f, r - y - endRadius)
    }
}
