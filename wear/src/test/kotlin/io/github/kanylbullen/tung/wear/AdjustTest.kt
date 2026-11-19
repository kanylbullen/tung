package io.github.kanylbullen.tung.wear

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.WEIGHT_RULER
import io.github.kanylbullen.tung.core.weightRulerFor
import io.github.kanylbullen.tung.core.wear.WatchExercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdjustTest {

    @Test
    fun weightFollowsTheRulersLocalStep() {
        assertEquals(30.0, AdjustField.Weight.step(29.0, 1), 0.0)
        assertEquals(32.5, AdjustField.Weight.step(30.0, 1), 0.0)
        assertEquals(30.0, AdjustField.Weight.step(32.5, -1), 0.0)
        assertEquals(29.0, AdjustField.Weight.step(30.0, -1), 0.0)
        // Off the grid: to the next point on it.
        assertEquals(32.5, AdjustField.Weight.step(31.0, 1), 0.0)
        assertEquals(85.0, AdjustField.Weight.step(80.0, 2), 0.0)
        assertEquals(0.0, AdjustField.Weight.step(0.0, -1), 0.0)
        assertEquals(999.0, AdjustField.Weight.step(999.0, 1), 0.0)
    }

    @Test
    fun aDumbbellStepsHalfKilosBelowTwenty() {
        val dumbbell = weightRulerFor(Equipment.DUMBBELL)
        assertEquals(8.0, AdjustField.Weight.step(7.5, 1, dumbbell), 0.0)
        assertEquals(19.5, AdjustField.Weight.step(20.0, -1, dumbbell), 0.0)
        assertEquals(21.0, AdjustField.Weight.step(20.0, 1, dumbbell), 0.0)
        assertEquals(0.5, AdjustField.Weight.stepAbove(12.5, dumbbell), 0.0)
        assertEquals(0.5, AdjustField.Weight.stepBelow(20.0, dumbbell), 0.0)
        assertEquals(dumbbell, WatchExercise("we", "Curl", emptyList(), equipment = "dumbbell").weightScale)
        assertEquals(WEIGHT_RULER, WatchExercise("we", "Curl", emptyList()).weightScale)
    }

    @Test
    fun repsStepByOne() {
        assertEquals(9.0, AdjustField.Reps.step(8.0, 1), 0.0)
        assertEquals(5.0, AdjustField.Reps.step(8.0, -3), 0.0)
        assertEquals(0.0, AdjustField.Reps.step(0.0, -1), 0.0)
    }

    @Test
    fun stepLabelsAndLimits() {
        assertEquals(1.0, AdjustField.Weight.stepBelow(30.0), 0.0)
        assertEquals(2.5, AdjustField.Weight.stepAbove(30.0), 0.0)
        assertEquals(1.0, AdjustField.Reps.stepAbove(8.0), 0.0)
        assertFalse(AdjustField.Weight.canStepDown(0.0))
        assertTrue(AdjustField.Weight.canStepUp(500.0))
        assertFalse(AdjustField.Reps.canStepUp(999.0))
    }

    @Test
    fun aBezelClickIsOneStep() {
        val stepper = RotaryStepper(thresholdPx = 40f)
        assertEquals(1, stepper.feed(96f))
        assertEquals(1, stepper.feed(96f))
        assertEquals(-1, stepper.feed(-96f))
    }

    @Test
    fun smallCrownEventsAddUp() {
        val stepper = RotaryStepper(thresholdPx = 40f)
        assertEquals(0, stepper.feed(15f))
        assertEquals(0, stepper.feed(15f))
        assertEquals(1, stepper.feed(15f))
        // 5 px left over, then 35 more make another step.
        assertEquals(1, stepper.feed(35f))
        assertEquals(0, stepper.feed(0f))
    }

    @Test
    fun turningBackDropsWhatWasGathered() {
        val stepper = RotaryStepper(thresholdPx = 40f)
        assertEquals(0, stepper.feed(30f))
        assertEquals(0, stepper.feed(-30f))
        assertEquals(-1, stepper.feed(-10f))
    }
}
