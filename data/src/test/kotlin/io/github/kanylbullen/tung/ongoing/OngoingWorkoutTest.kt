package io.github.kanylbullen.tung.ongoing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kanylbullen.tung.core.OngoingStatus
import io.github.kanylbullen.tung.data.Exercise
import io.github.kanylbullen.tung.data.SetEntry
import io.github.kanylbullen.tung.data.Workout
import io.github.kanylbullen.tung.data.WorkoutExercise
import io.github.kanylbullen.tung.data.WorkoutExerciseWithSets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * The Live Update chip's text ([chipText]) and the current set's numbers that
 * [ongoingWorkout] hands it: "80×8" for the set Log set would tick, the weight
 * in the locale's decimal separator; nothing for a set without reps or when
 * every set is ticked.
 */
@RunWith(RobolectricTestRunner::class)
class OngoingWorkoutTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val workout = Workout(id = "w", startedAt = 1_000, updatedAt = 1_000)
    private lateinit var defaultLocale: Locale

    @Before
    fun fixLocale() {
        defaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.UK)
    }

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    /** One exercise with its sets as (weight, reps, ticked), in order. */
    private fun exercise(key: String, position: Int, vararg sets: Triple<Double, Int, Boolean>) = WorkoutExerciseWithSets(
        workoutExercise = WorkoutExercise(id = "we-$key", workoutId = "w", exerciseId = "e-$key", position = position, updatedAt = 1_000),
        exercise = Exercise(id = "e-$key", name = key, nameKey = key, muscleGroup = "chest", equipment = "barbell", isCustom = false, updatedAt = 0),
        sets = sets.mapIndexed { i, (kg, reps, done) ->
            SetEntry(id = "s-$key-$i", workoutExerciseId = "we-$key", position = i, weightKg = kg, reps = reps, completed = done, updatedAt = 1_000L + i)
        },
    )

    private fun ongoing(vararg exercises: WorkoutExerciseWithSets): OngoingWorkout =
        ongoingWorkout(workout, exercises.toList(), emptyMap()) { it.exercise.name }

    @Test
    fun theChipShowsTheCurrentSetsNumbers() {
        val bench = ongoing(exercise("bench", 0, Triple(80.0, 8, true), Triple(82.5, 6, false)))
        assertEquals("s-bench-1", bench.logSetId)
        assertEquals(82.5, bench.logSetWeightKg)
        assertEquals(6, bench.logSetReps)
        assertTrue(bench.logSetHasReps)
        assertEquals("82.5×6", bench.chipText(context))

        // The weight is written as the app writes it, with the locale's separator.
        Locale.setDefault(Locale.forLanguageTag("sv-SE"))
        assertEquals("82,5×6", bench.chipText(context))

        // A bodyweight set is 0 kg and still a set.
        assertEquals("0×12", ongoing(exercise("pullup", 0, Triple(0.0, 12, false))).chipText(context))
    }

    @Test
    fun theChipIsEmptyWithoutRepsAndWhenEverySetIsTicked() {
        // The first set ever is 0 × 0: Log set opens the reps sheet, and the chip says nothing.
        val fresh = ongoing(exercise("bench", 0, Triple(0.0, 0, false)))
        assertEquals("s-bench-0", fresh.logSetId)
        assertFalse(fresh.logSetHasReps)
        assertNull(fresh.chipText(context))

        // Every set ticked: the action is Add set, and the chip falls back to the icon.
        val done = ongoing(exercise("bench", 0, Triple(80.0, 8, true)))
        assertNull(done.logSetId)
        assertNull(done.logSetWeightKg)
        assertNull(done.logSetReps)
        assertFalse(done.logSetHasReps)
        assertEquals(OngoingStatus.AllLogged("bench"), done.status)
        assertEquals("we-bench", done.addSetWorkoutExerciseId)
        assertNull(done.chipText(context))
    }
}
