package io.github.kanylbullen.tung.core.muscles

import io.github.kanylbullen.tung.core.ExerciseMuscles
import io.github.kanylbullen.tung.core.Muscle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MuscleWorkTest {

    private val bench = ExerciseMuscles(listOf(Muscle.CHEST), listOf(Muscle.TRICEPS, Muscle.FRONT_DELTS))
    private val curl = ExerciseMuscles(listOf(Muscle.BICEPS), listOf(Muscle.FOREARMS))
    private val deadlift = ExerciseMuscles(
        listOf(Muscle.LOWER_BACK, Muscle.GLUTES, Muscle.HAMSTRINGS),
        listOf(Muscle.QUADS, Muscle.TRAPS, Muscle.LATS, Muscle.FOREARMS),
    )

    /** One set of bench press: chest a whole set, triceps and front delts half each; ties in muscle order. */
    @Test
    fun primaryCountsAWholeSetAndSecondaryHalf() {
        val breakdown = muscleBreakdown(listOf(ExerciseWork(bench, 1)))
        assertEquals(listOf(Muscle.CHEST, Muscle.FRONT_DELTS, Muscle.TRICEPS), breakdown.muscles.map { it.muscle })
        assertEquals(listOf(1.0, 0.5, 0.5), breakdown.muscles.map { it.sets })
        assertEquals(listOf(50, 25, 25), breakdown.muscles.map { it.percent })
        assertEquals(1.0, breakdown.intensity(Muscle.CHEST), 1e-9)
        assertEquals(0.5, breakdown.intensity(Muscle.TRICEPS), 1e-9)
        assertEquals(0.0, breakdown.intensity(Muscle.QUADS), 1e-9)
    }

    /**
     * Two sets each of bench and curls: 2 + 2 + 1 + 1 + 1 of 7 rounds to 28,
     * 28, 14, 14, 14, and the two largest remainders take the 2 left over.
     */
    @Test
    fun percentagesAddUpToAHundred() {
        val breakdown = muscleBreakdown(listOf(ExerciseWork(bench, 2), ExerciseWork(curl, 2)))
        assertEquals(
            listOf(Muscle.CHEST to 29, Muscle.BICEPS to 29, Muscle.FRONT_DELTS to 14, Muscle.TRICEPS to 14, Muscle.FOREARMS to 14),
            breakdown.muscles.map { it.muscle to it.percent },
        )
        assertEquals(100, breakdown.muscles.sumOf { it.percent })
    }

    /** A muscle in several exercises adds up; one that is primary in one and secondary in another gets both. */
    @Test
    fun creditAddsUpAcrossExercises() {
        val breakdown = muscleBreakdown(listOf(ExerciseWork(deadlift, 3), ExerciseWork(curl, 2)))
        val forearms = breakdown.muscles.single { it.muscle == Muscle.FOREARMS }
        assertEquals(3 * 0.5 + 2 * 0.5, forearms.sets, 1e-9)
        assertEquals(3.0, breakdown.muscles.single { it.muscle == Muscle.GLUTES }.sets, 1e-9)
        assertEquals(100, breakdown.muscles.sumOf { it.percent })
    }

    /** A muscle listed as both primary and secondary counts once, as primary. */
    @Test
    fun aMuscleIsNotCountedTwiceInOneExercise() {
        val odd = ExerciseMuscles(listOf(Muscle.QUADS), listOf(Muscle.QUADS, Muscle.GLUTES))
        val breakdown = muscleBreakdown(listOf(ExerciseWork(odd, 2)))
        assertEquals(listOf(Muscle.QUADS to 2.0, Muscle.GLUTES to 1.0), breakdown.muscles.map { it.muscle to it.sets })
    }

    /** Custom exercises have no muscles: their sets are counted apart; exercises without logged sets count nowhere. */
    @Test
    fun customExercisesAreUncounted() {
        val breakdown = muscleBreakdown(listOf(ExerciseWork(null, 3), ExerciseWork(curl, 0), ExerciseWork(bench, 1)))
        assertEquals(3, breakdown.uncountedSets)
        assertEquals(listOf(Muscle.CHEST, Muscle.FRONT_DELTS, Muscle.TRICEPS), breakdown.muscles.map { it.muscle })

        val none = muscleBreakdown(listOf(ExerciseWork(null, 2)))
        assertTrue(none.isEmpty)
        assertEquals(2, none.uncountedSets)
        assertEquals(0.0, none.intensity(Muscle.CHEST), 1e-9)
        assertTrue(muscleBreakdown(emptyList()).isEmpty)
    }

    @Test
    fun wholePercentsByLargestRemainder() {
        assertEquals(listOf(34, 33, 33), wholePercents(listOf(1.0, 1.0, 1.0)))
        assertEquals(listOf(100), wholePercents(listOf(4.0)))
        assertEquals(listOf(67, 33), wholePercents(listOf(2.0, 1.0)))
        // A sliver rounds to nothing rather than taking a point from a larger share.
        assertEquals(listOf(100, 0), wholePercents(listOf(400.0, 0.5)))
        assertEquals(listOf(0, 0), wholePercents(listOf(0.0, 0.0)))
        assertEquals(emptyList<Int>(), wholePercents(emptyList()))
    }
}
