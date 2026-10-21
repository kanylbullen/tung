package io.github.kanylbullen.tung.core.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The Jefit adapter against `import/jefit/export-synthetic.csv`: made-up rows
 * in the layout of a real export, with each quirk the real one has (see
 * [JefitImport]).
 */
class JefitImportTest {

    private val zone = ZoneId.of("Europe/Stockholm")
    private val text = ImportTestFiles.fixture("jefit/export-synthetic.csv")
    private val bundle = JefitImport.parse(text, zone)

    private fun millis(local: String) = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    private fun workoutAt(local: String) = bundle.workouts.single { it.startedAt == millis(local) }

    @Test
    fun recognisesAJefitExportAndNothingElse() {
        assertTrue(JefitImport.recognises(text))
        assertEquals(JefitImport, adapterFor(text))
        assertFalse(JefitImport.recognises("Date,Workout Name,Exercise Name,Set Order,Weight,Reps\n"))
        assertNull(adapterFor("hello"))
    }

    @Test
    fun oneWorkoutPerSessionAndPerDayOfLooseLogsOldestFirst() {
        assertEquals("jefit", bundle.source)
        assertEquals(
            listOf("2023-02-24T18:00", "2023-02-27T12:00", "2023-03-06T18:00", "2023-03-13T18:00").map(::millis),
            bundle.workouts.map { it.startedAt },
        )
        assertEquals(12, bundle.setCount)
        assertEquals(millis("2023-02-24T18:00")..millis("2023-03-13T18:00"), bundle.dateRange)
    }

    @Test
    fun setRowsAreInLbsAndRoundedToHalfAKilo() {
        val deadlift = workoutAt("2023-03-06T18:00").exercises.first()
        assertEquals("Barbell Deadlift", deadlift.name)
        assertEquals(listOf(60.0 to 5, 70.0 to 5, 80.0 to 5), deadlift.sets.map { it.weightKg to it.reps })
        assertEquals(millis("2023-03-06T19:00"), workoutAt("2023-03-06T18:00").endedAt)
    }

    @Test
    fun aLogWithoutSetRowsReadsItsTextInTheAccountsUnit() {
        val press = workoutAt("2023-03-13T18:00").exercises.first()
        assertEquals("Dumbbell Shoulder Press", press.name)
        assertEquals(listOf(12.0 to 10, 12.0 to 10), press.sets.map { it.weightKg to it.reps })
    }

    @Test
    fun setRowsWinOverTheTextWhenALogHasThem() {
        // Heel Walking's text is five fields per set; its rows say one set of 8 and one without reps.
        val heel = workoutAt("2023-03-06T18:00").exercises.single { it.name == "Heel Walking" }
        assertEquals(listOf(0.0 to 8), heel.sets.map { it.weightKg to it.reps })
    }

    @Test
    fun aSessionStartingAt1970TakesItsLogsTimes() {
        val workout = workoutAt("2023-03-13T18:00")
        assertEquals(listOf("Dumbbell Shoulder Press", "Barbell Curl"), workout.exercises.map { it.name })
        assertEquals(millis("2023-03-13T19:00"), workout.endedAt)
        assertTrue(bundle.workouts.none { it.startedAt < millis("2000-01-01T00:00") })
    }

    @Test
    fun looseLogsAdoptASessionOfTheirDayThatHasNoLogs() {
        val adopted = workoutAt("2023-02-24T18:00")
        assertEquals(listOf("Barbell Squat", "Barbell Bench Press"), adopted.exercises.map { it.name })
        assertEquals(listOf(40.0 to 8, 50.0 to 8), adopted.exercises[0].sets.map { it.weightKg to it.reps })
        // 30 × 0 is not a logged set.
        assertEquals(listOf(30.0 to 8), adopted.exercises[1].sets.map { it.weightKg to it.reps })
        assertEquals(millis("2023-02-24T19:00"), adopted.endedAt)
        // A day with no session gets one at noon on its date.
        assertEquals(listOf("Barbell Bent Over Row"), workoutAt("2023-02-27T12:00").exercises.map { it.name })
    }

    @Test
    fun warmUpAndDropSetsAreImportedAsOrdinarySetsAndCounted() {
        assertEquals(mapOf("warm-up" to 1, "drop" to 1), bundle.setTypes)
        val curl = workoutAt("2023-03-13T18:00").exercises.single { it.name == "Barbell Curl" }
        assertEquals(listOf(25.0 to 8, 20.0 to 10), curl.sets.map { it.weightKg to it.reps })
    }

    @Test
    fun everythingLeftOutIsCountedWithItsReason() {
        assertEquals(
            mapOf(
                SkipReason.SESSION_WITHOUT_EXERCISES to 2,
                SkipReason.CARDIO to 1,
                SkipReason.CARDIO_LOG to 1,
                SkipReason.LOG_WITHOUT_SETS to 1,
                // 30 × 0, a set row without reps, and the only set of a log ("0x0"), which then has none.
                SkipReason.SET_WITHOUT_REPS to 3,
                SkipReason.SET_WITHOUT_LOG to 1,
                SkipReason.STRAY_HEADER to 1,
                SkipReason.ROUTINE_WITHOUT_EXERCISES to 1,
                SkipReason.DUPLICATE_ROUTINE to 1,
            ),
            bundle.skipped,
        )
        assertTrue(bundle.workouts.flatMap { it.exercises }.none { it.name.trim() == "swimming" })
    }

    @Test
    fun everySetRowIsAccountedFor() {
        assertEquals(SetRowAccount(read = 9, used = 6, skipped = 3), bundle.setRows)
    }

    @Test
    fun routinesAreTheDaysWithExercisesInJefitsOrder() {
        assertEquals(listOf("Home Gym · Day A", "Test Five · Monday", "Test Five · Thursday"), bundle.routines.map { it.name })
        val dayA = bundle.routines[0]
        assertEquals(listOf("Barbell Bench Press", "Barbell Squat"), dayA.exercises.map { it.name })
        assertEquals(listOf(4 to 8, 3 to 5), dayA.exercises.map { it.targetSets to it.targetReps })
        // targetrep 0 is no target.
        assertEquals(listOf(1 to 5, 2 to null), bundle.routines[1].exercises.map { it.targetSets to it.targetReps })
    }

    @Test
    fun customExercisesAreNamed() {
        assertEquals(setOf("swimming"), bundle.customExercises)
    }

    @Test
    fun idsAreDeterministicAndUnique() {
        val again = JefitImport.parse(text, zone)
        assertEquals(bundle, again)
        val ids = bundle.workouts.flatMap { w -> listOf(w.id) + w.exercises.flatMap { e -> listOf(e.id) + e.sets.map { it.id } } } +
            bundle.routines.flatMap { r -> listOf(r.id) + r.exercises.map { it.id } }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(importId("jefit", "workout", "4242", "session", "1"), workoutAt("2023-03-06T18:00").id)
    }

    @Test
    fun theNamesToResolveAreThoseOfWorkoutsAndRoutines() {
        assertEquals(
            setOf(
                "Barbell Squat", "Barbell Bench Press", "Barbell Bent Over Row", "Barbell Deadlift", "Heel Walking",
                "Dumbbell Shoulder Press", "Barbell Curl",
            ),
            bundle.importExerciseNames(),
        )
    }
}
