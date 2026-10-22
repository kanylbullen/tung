package io.github.kanylbullen.tung.core.muscles

import io.github.kanylbullen.tung.core.Muscle
import io.github.kanylbullen.tung.core.Muscle.ABDUCTORS
import io.github.kanylbullen.tung.core.Muscle.ABS
import io.github.kanylbullen.tung.core.Muscle.ADDUCTORS
import io.github.kanylbullen.tung.core.Muscle.BICEPS
import io.github.kanylbullen.tung.core.Muscle.CALVES
import io.github.kanylbullen.tung.core.Muscle.CHEST
import io.github.kanylbullen.tung.core.Muscle.FOREARMS
import io.github.kanylbullen.tung.core.Muscle.FRONT_DELTS
import io.github.kanylbullen.tung.core.Muscle.GLUTES
import io.github.kanylbullen.tung.core.Muscle.HAMSTRINGS
import io.github.kanylbullen.tung.core.Muscle.HIP_FLEXORS
import io.github.kanylbullen.tung.core.Muscle.LATS
import io.github.kanylbullen.tung.core.Muscle.LOWER_BACK
import io.github.kanylbullen.tung.core.Muscle.NECK
import io.github.kanylbullen.tung.core.Muscle.OBLIQUES
import io.github.kanylbullen.tung.core.Muscle.QUADS
import io.github.kanylbullen.tung.core.Muscle.REAR_DELTS
import io.github.kanylbullen.tung.core.Muscle.SIDE_DELTS
import io.github.kanylbullen.tung.core.Muscle.TRAPS
import io.github.kanylbullen.tung.core.Muscle.TRICEPS
import io.github.kanylbullen.tung.core.Muscle.UPPER_BACK
import io.github.kanylbullen.tung.core.muscles.BodySide.BACK
import io.github.kanylbullen.tung.core.muscles.BodySide.FRONT
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Which figure of the muscle map: seen from the front or from the back. */
enum class BodySide { FRONT, BACK }

/** A point in one figure, normalised: [x] 0 (left edge) to 1 (right edge), [y] 0 (top of the head) to 1 (soles). */
data class BodyPoint(val x: Float, val y: Float)

/** One closed polygon of [muscle] on the [side] figure. A bilateral muscle has two, one per half. */
data class BodyRegion(val muscle: Muscle, val side: BodySide, val points: List<BodyPoint>)

/**
 * The muscle map's drawing data (issue #54): two body silhouettes, front and
 * back, and the regions of every [Muscle] on them, as normalised polygons a
 * client scales to its canvas (a figure's box is [ASPECT] wide per unit of
 * height). Hand-authored, simple and recognisable rather than anatomical: a
 * region is where a lifter would point, not where the muscle attaches.
 *
 * Authored on a 100 × 200 grid (the numbers below), on the figure's left half
 * for bilateral muscles, which are mirrored to the right half; the neck,
 * which sits on the centre line, is drawn whole. Some muscles are on one
 * side only: the chest, abs, obliques, hip flexors, front delts, biceps and
 * quads on the front, the upper back, lats, lower back, rear delts, triceps,
 * glutes and hamstrings on the back. `BodyMapTest` checks that every muscle
 * has a region and that every region lies inside its silhouette.
 */
object BodyMap {
    /** A figure's width over its height. */
    const val ASPECT = 0.5f

    private const val GRID_W = 100f
    private const val GRID_H = 200f

    /** The outline of each figure, a closed polygon. The back view has the front's outline. */
    val outlines: Map<BodySide, List<BodyPoint>> by lazy {
        val outline = silhouette()
        mapOf(FRONT to outline, BACK to outline.toList())
    }

    val regions: List<BodyRegion> by lazy { authored() }

    /** Every region of [muscle], on both figures. */
    fun regionsOf(muscle: Muscle): List<BodyRegion> = regions.filter { it.muscle == muscle }

    /** The regions of [side], in drawing order. */
    fun regionsOn(side: BodySide): List<BodyRegion> = regions.filter { it.side == side }

    private fun point(x: Float, y: Float) = BodyPoint(x / GRID_W, y / GRID_H)

    private fun points(coords: FloatArray): List<Pair<Float, Float>> {
        require(coords.size % 2 == 0 && coords.size >= 6) { "a polygon needs three or more x, y pairs" }
        return coords.toList().chunked(2) { (x, y) -> x to y }
    }

    /** The left half of the head: an ellipse from the crown to the chin. */
    private fun headLeftHalf(): List<Pair<Float, Float>> = (0..12).map { i ->
        val a = PI / 2 + PI * i / 12
        (50 + 8.5 * cos(a)).toFloat() to (13 - 11 * sin(a)).toFloat()
    }

    /** The left half of the outline, crown to crotch; mirrored for the right half. */
    private fun silhouette(): List<BodyPoint> {
        val left = headLeftHalf() + points(
            floatArrayOf(
                46f, 24f, 45.5f, 27f, 38f, 29.5f, 31f, 31f, 26f, 33.5f, 22.5f, 38f,
                21f, 44f, 19.5f, 54f, 18f, 64f, 16.5f, 74f, 15f, 82f, 13.2f, 93f,
                12f, 103f, 10f, 108f, 9.5f, 114f, 11.5f, 118f, 14f, 117f, 15f, 111f,
                16f, 106f, 18.5f, 95f, 21f, 84f, 23.5f, 72f, 25.5f, 62f, 28f, 54f,
                30f, 62f, 31f, 74f, 32.5f, 86f, 31.5f, 96f, 30f, 104f, 29.5f, 116f,
                30.5f, 130f, 32.5f, 144f, 32f, 152f, 31.5f, 162f, 33f, 174f, 35.5f, 186f,
                32f, 192f, 32.5f, 196f, 43.5f, 196.5f, 44f, 190f, 43.5f, 184f, 44.5f, 172f,
                45f, 160f, 45.5f, 150f, 46.5f, 136f, 47.5f, 124f, 49f, 115f, 50f, 114f,
            ),
        )
        val right = left.reversed().filter { (x, _) -> x != 50f }.map { (x, y) -> GRID_W - x to y }
        return (left + right).map { (x, y) -> point(x, y) }
    }

    private class Builder {
        val regions = mutableListOf<BodyRegion>()

        /** A region authored on the left half; [mirrored] adds its mirror image on the right half. */
        fun region(muscle: Muscle, side: BodySide, mirrored: Boolean, vararg coords: Float) {
            val left = points(coords)
            regions += BodyRegion(muscle, side, left.map { (x, y) -> point(x, y) })
            if (mirrored) regions += BodyRegion(muscle, side, left.map { (x, y) -> point(GRID_W - x, y) })
        }
    }

    private fun authored(): List<BodyRegion> = Builder().apply {
        // Front
        region(NECK, FRONT, mirrored = false,
            46.5f, 24.5f, 53.5f, 24.5f, 53f, 28f, 47f, 28f,
        )
        region(TRAPS, FRONT, mirrored = true,
            46f, 27.5f, 39f, 30f, 33f, 31.5f, 40f, 32.5f, 46.5f, 31f,
        )
        region(FRONT_DELTS, FRONT, mirrored = true,
            31f, 32f, 26.5f, 34f, 24.5f, 38.5f, 25f, 45f, 28.5f, 46f, 31.5f, 40f,
        )
        region(SIDE_DELTS, FRONT, mirrored = true,
            25.5f, 34.5f, 22.5f, 38.5f, 21.3f, 44.5f, 21.5f, 48f, 24f, 46f, 24f, 39f,
        )
        region(CHEST, FRONT, mirrored = true,
            49.5f, 33.5f, 40f, 33f, 33f, 35f, 31.5f, 41f, 33f, 49f, 39f, 53f, 46f, 52.5f, 49.5f, 50f,
        )
        region(BICEPS, FRONT, mirrored = true,
            27.5f, 48f, 24f, 48f, 21.5f, 54f, 20.5f, 63f, 20.5f, 72f, 23f, 74f, 25f, 64f, 27.5f, 55f,
        )
        region(FOREARMS, FRONT, mirrored = true,
            21f, 77.5f, 17.5f, 83f, 15.3f, 92f, 14.2f, 101f, 16.5f, 101f, 19f, 91f, 20.5f, 84f,
        )
        region(ABS, FRONT, mirrored = true,
            49.3f, 56f, 43.5f, 56.5f, 42.5f, 66f, 42.5f, 80f, 44f, 92f, 49.3f, 97f,
        )
        region(OBLIQUES, FRONT, mirrored = true,
            41.5f, 57f, 35.5f, 55f, 32f, 62f, 33f, 75f, 34.5f, 88f, 41.5f, 93f, 40.8f, 80f, 40.8f, 66f,
        )
        region(HIP_FLEXORS, FRONT, mirrored = true,
            37f, 99.5f, 42f, 101f, 47f, 108f, 46.5f, 113f, 41.5f, 111f, 37.5f, 105f,
        )
        region(ABDUCTORS, FRONT, mirrored = true,
            32.5f, 97f, 35.5f, 101f, 35f, 112f, 32.5f, 120f, 31f, 111f, 31.3f, 102f,
        )
        region(QUADS, FRONT, mirrored = true,
            36.5f, 108f, 33.5f, 120f, 33f, 132f, 35f, 143f, 39.5f, 147f, 44f, 145f, 45f, 133f, 44.5f, 120f,
            41f, 113f,
        )
        region(ADDUCTORS, FRONT, mirrored = true,
            47.5f, 113f, 48.7f, 116f, 47f, 125f, 46.3f, 133f, 45.8f, 122f,
        )
        region(CALVES, FRONT, mirrored = true,
            33.5f, 155f, 32.8f, 164f, 34.5f, 175f, 37f, 172f, 37.5f, 160f, 36.5f, 153f,
        )
        // Back
        region(NECK, BACK, mirrored = false,
            46.5f, 22f, 53.5f, 22f, 53.5f, 26.5f, 46.5f, 26.5f,
        )
        region(TRAPS, BACK, mirrored = true,
            50f, 26.5f, 45f, 27.5f, 36f, 31f, 31.5f, 33f, 40f, 36f, 45.5f, 42f, 50f, 50f,
        )
        region(REAR_DELTS, BACK, mirrored = true,
            31f, 33.5f, 26.5f, 34.5f, 24.5f, 38.5f, 25f, 45f, 28.5f, 46f, 31.5f, 40f,
        )
        region(SIDE_DELTS, BACK, mirrored = true,
            25.5f, 34.5f, 22.5f, 38.5f, 21.3f, 44.5f, 21.5f, 48f, 24f, 46f, 24f, 39f,
        )
        region(UPPER_BACK, BACK, mirrored = true,
            39f, 37.5f, 33f, 35.5f, 32f, 42f, 34f, 50f, 40f, 53f, 46f, 50.5f, 44.5f, 43f,
        )
        region(LATS, BACK, mirrored = true,
            33.5f, 52f, 30.5f, 58f, 31.2f, 70f, 33.5f, 80f, 39f, 84f, 45.5f, 78f, 45f, 66f, 41f, 56f,
        )
        region(LOWER_BACK, BACK, mirrored = true,
            45.5f, 80.5f, 40f, 86f, 34f, 85f, 33.5f, 92f, 38f, 99f, 49.5f, 99f, 49.5f, 82f,
        )
        region(TRICEPS, BACK, mirrored = true,
            27.5f, 48f, 24f, 48f, 21.5f, 54f, 20.5f, 63f, 20.5f, 72f, 23f, 74f, 25f, 64f, 27.5f, 55f,
        )
        region(FOREARMS, BACK, mirrored = true,
            21f, 77.5f, 17.5f, 83f, 15.3f, 92f, 14.2f, 101f, 16.5f, 101f, 19f, 91f, 20.5f, 84f,
        )
        region(ABDUCTORS, BACK, mirrored = true,
            32.5f, 96f, 36.5f, 99f, 36f, 104f, 32f, 108f, 30.8f, 102f,
        )
        region(GLUTES, BACK, mirrored = true,
            37.5f, 101f, 49.5f, 101f, 49.5f, 113f, 46f, 120f, 38f, 121f, 33f, 116f, 32.5f, 109f,
        )
        region(HAMSTRINGS, BACK, mirrored = true,
            33.5f, 123f, 33f, 133f, 35f, 145f, 40f, 148f, 44.5f, 145f, 46f, 133f, 46.5f, 124f, 40f, 123f,
        )
        region(ADDUCTORS, BACK, mirrored = true,
            47.3f, 121f, 48.8f, 116f, 47f, 126f, 46.8f, 132f,
        )
        region(CALVES, BACK, mirrored = true,
            33.5f, 154f, 32.8f, 164f, 35f, 176f, 38.5f, 178f, 42f, 176f, 44.5f, 165f, 44f, 155f, 39f, 152f,
        )
    }.regions
}
