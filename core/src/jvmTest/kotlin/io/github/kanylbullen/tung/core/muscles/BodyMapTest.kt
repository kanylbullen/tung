package io.github.kanylbullen.tung.core.muscles

import io.github.kanylbullen.tung.core.Muscle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyMapTest {

    @Test
    fun everyMuscleHasAtLeastOneRegion() {
        assertEquals(21, Muscle.entries.size)
        val missing = Muscle.entries.filter { BodyMap.regionsOf(it).isEmpty() }
        assertEquals(emptyList<Muscle>(), missing)
    }

    @Test
    fun bothFiguresHaveAnOutline() {
        BodySide.entries.forEach { side ->
            val outline = BodyMap.outlines[side]
            assertTrue("$side has no outline", outline != null && outline.size >= 3)
        }
    }

    @Test
    fun everyPointIsNormalisedAndEveryRegionIsAPolygon() {
        val all = BodyMap.regions.flatMap { it.points } + BodyMap.outlines.values.flatten()
        all.forEach { p -> assertTrue("$p outside 0..1", p.x in 0f..1f && p.y in 0f..1f) }
        BodyMap.regions.forEach { r -> assertTrue("${r.muscle} on ${r.side}: ${r.points.size} points", r.points.size >= 3) }
    }

    /** Every corner of every region lies inside (or on) its figure's outline, so nothing is drawn off the body. */
    @Test
    fun everyRegionLiesInsideItsSilhouette() {
        BodyMap.regions.forEach { region ->
            val outline = BodyMap.outlines.getValue(region.side)
            region.points.forEach { p ->
                assertTrue("${region.muscle} on ${region.side}: $p is off the body", inside(p, outline))
            }
        }
    }

    /** Bilateral muscles come in mirrored pairs, so the map is symmetric. */
    @Test
    fun regionsAreSymmetric() {
        BodySide.entries.forEach { side ->
            val shapes = BodyMap.regionsOn(side).map { r -> r.muscle to r.points.map { (it.x * 1000).toInt() to (it.y * 1000).toInt() }.toSet() }
            shapes.forEach { (muscle, points) ->
                val mirrored = points.map { (x, y) -> 1000 - x to y }.toSet()
                assertTrue(
                    "$muscle on $side has no mirror image",
                    shapes.any { (m, other) -> m == muscle && close(other, mirrored) },
                )
            }
        }
    }

    @Test
    fun theFrontShowsTheChestAndTheBackTheGlutes() {
        assertEquals(setOf(BodySide.FRONT), BodyMap.regionsOf(Muscle.CHEST).map { it.side }.toSet())
        assertEquals(setOf(BodySide.BACK), BodyMap.regionsOf(Muscle.GLUTES).map { it.side }.toSet())
        assertEquals(setOf(BodySide.FRONT, BodySide.BACK), BodyMap.regionsOf(Muscle.SIDE_DELTS).map { it.side }.toSet())
    }

    private fun close(a: Set<Pair<Int, Int>>, b: Set<Pair<Int, Int>>): Boolean =
        a.size == b.size && a.all { (x, y) -> b.any { (bx, by) -> kotlin.math.abs(bx - x) <= 1 && kotlin.math.abs(by - y) <= 1 } }

    /** Even-odd ray casting, with points on an edge counted as inside. */
    private fun inside(p: BodyPoint, polygon: List<BodyPoint>): Boolean {
        var crossings = 0
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            if (onSegment(p, a, b)) return true
            if ((a.y > p.y) != (b.y > p.y)) {
                val x = a.x + (p.y - a.y) * (b.x - a.x) / (b.y - a.y)
                if (p.x < x) crossings++
            }
        }
        return crossings % 2 == 1
    }

    private fun onSegment(p: BodyPoint, a: BodyPoint, b: BodyPoint): Boolean {
        val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
        if (kotlin.math.abs(cross) > 1e-6f) return false
        return p.x in minOf(a.x, b.x) - 1e-6f..maxOf(a.x, b.x) + 1e-6f && p.y in minOf(a.y, b.y) - 1e-6f..maxOf(a.y, b.y) + 1e-6f
    }
}
