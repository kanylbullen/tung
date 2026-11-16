@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.kanylbullen.tung.core.importing

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Strong adapter against `import/strong/export-synthetic.csv` (newer
 * format) and `import/strong/export-synthetic-older.csv` (older format).
 */
class StrongImportTest {

    private val zone = TimeZone.of("Europe/Stockholm")
    private val newerText = ImportTestFiles.fixture("strong/export-synthetic.csv")
    private val olderText = ImportTestFiles.fixture("strong/export-synthetic-older.csv")
    private val bundle = StrongImport.parse(newerText, zone)
    private val olderBundle = StrongImport.parse(olderText, zone)

    private fun millis(local: String, tz: TimeZone = zone) =
        LocalDateTime.parse(local).toInstant(tz).toEpochMilliseconds()

    private fun workoutAt(local: String) = bundle.workouts.single { it.startedAt == millis(local) }

    @Test
    fun recognisesBothStrongFormatsAndNothingElse() {
        assertTrue(StrongImport.recognises(newerText))
        assertTrue(StrongImport.recognises(olderText))
        assertEquals(StrongImport, adapterFor(newerText))
        assertEquals(StrongImport, adapterFor(olderText))

        val semicolonHeader = newerText.replace(',', ';')
        assertTrue(StrongImport.recognises(semicolonHeader))
        assertEquals(StrongImport, adapterFor(semicolonHeader))

        val jefitText = ImportTestFiles.fixture("jefit/export-synthetic.csv")
        assertFalse(StrongImport.recognises(jefitText))
        assertFalse(JefitImport.recognises(newerText))
        assertFalse(JefitImport.recognises(olderText))
        assertNull(adapterFor("hello"))
    }

    @Test
    fun workoutsAreOldestFirstWithSeparateWorkoutsOnTheSameDay() {
        assertEquals("strong", bundle.source)
        assertTrue(bundle.routines.isEmpty())
        assertTrue(bundle.customExercises.isEmpty())
        assertEquals(
            listOf("2024-05-03T17:00", "2024-05-10T07:30", "2024-05-10T18:00").map { millis(it) },
            bundle.workouts.map { it.startedAt },
        )
        assertEquals(6, bundle.setCount)
        assertEquals(millis("2024-05-03T17:00")..millis("2024-05-10T18:00"), bundle.dateRange)
    }

    @Test
    fun lbsInNewerFormatAreConvertedAndRoundedToHalfAKilo() {
        val push = workoutAt("2024-05-10T07:30")
        // Quoted note with comma and newline did not split or drop the warm-up row.
        val bench = push.exercises.single()
        assertEquals("Bench Press (Barbell)", bench.name)
        assertEquals(listOf(61.0 to 8, 102.0 to 5, 61.0 to 10), bench.sets.map { it.weightKg to it.reps })

        val pull = workoutAt("2024-05-10T18:00")
        assertEquals(listOf("Pull Up", "Barbell Row"), pull.exercises.map { it.name })
        assertEquals(listOf(0.0 to 8), pull.exercises[0].sets.map { it.weightKg to it.reps })
        assertEquals(listOf(61.0 to 6), pull.exercises[1].sets.map { it.weightKg to it.reps })
    }

    @Test
    fun kgInNewerFormatAndOlderFormatDefaultToKg() {
        val kgCsv = """
            Workout #,Date,Workout Name,Duration (sec),Exercise Name,Set Order,Weight (kg),Reps,Distance (meters),Seconds,Notes,Workout Notes,RPE
            1,2024-06-01 10:00:00,Upper,1800,Bench Press (Barbell),1,82.5,6,0,0,,,
        """.trimIndent()
        val kgBundle = StrongImport.parse(kgCsv, zone)
        assertEquals(listOf(82.5 to 6), kgBundle.workouts.single().exercises.single().sets.map { it.weightKg to it.reps })

        assertEquals(3, olderBundle.workouts.size)
        assertEquals(listOf(60.0 to 5, 100.0 to 5), olderBundle.workouts[0].exercises.single().sets.map { it.weightKg to it.reps })
        assertEquals(listOf(82.5 to 6), olderBundle.workouts[1].exercises.single().sets.map { it.weightKg to it.reps })

        val olderAsLbs = StrongImport.parse(olderText, zone, defaultWeightInLbs = true)
        assertEquals(listOf(27.0 to 5, 45.5 to 5), olderAsLbs.workouts[0].exercises.single().sets.map { it.weightKg to it.reps })
    }

    @Test
    fun warmUpDropAndFailureSetsAreCountedAndImportedAsOrdinarySets() {
        assertEquals(mapOf("warm-up" to 1, "drop" to 1, "failure" to 1), bundle.setTypes)
    }

    @Test
    fun nonSetMarkersCardioAndZeroRepSetsAreSkippedAndCounted() {
        assertEquals(
            mapOf(
                SkipReason.CARDIO to 1,
                SkipReason.SET_WITHOUT_REPS to 1,
                SkipReason.SET_UNREADABLE to 1,
            ),
            bundle.skipped,
        )
        assertEquals(SetRowAccount(read = 9, used = 6, skipped = 3), bundle.setRows)
    }

    @Test
    fun durationsAndMissingDurationsComputeEndTimeInGivenZone() {
        assertEquals(millis("2024-05-10T08:15"), workoutAt("2024-05-10T07:30").endedAt)
        assertEquals(millis("2024-05-10T18:50"), workoutAt("2024-05-10T18:00").endedAt)
        assertEquals(millis("2024-05-03T18:00"), workoutAt("2024-05-03T17:00").endedAt)

        // Older format: 1h 5m, 45m, and missing duration (falls back to +1 h).
        assertEquals(millis("2024-04-20T10:05"), olderBundle.workouts[0].endedAt)
        assertEquals(millis("2024-04-25T19:00"), olderBundle.workouts[1].endedAt)
        assertEquals(millis("2024-04-28T11:00"), olderBundle.workouts[2].endedAt)

        val nyZone = TimeZone.of("America/New_York")
        val nyBundle = StrongImport.parse(newerText, nyZone)
        assertEquals(millis("2024-05-03T17:00", nyZone), nyBundle.workouts.first().startedAt)
        assertNotEquals(bundle.workouts.first().startedAt, nyBundle.workouts.first().startedAt)
    }

    @Test
    fun semicolonSeparatedCsvParsesIdentically() {
        val semicolonCsv = """
            Date;Workout Name;Duration;Exercise Name;Set Order;Weight;Reps;Distance;Seconds;Notes;Workout Notes;RPE
            2024-04-20 09:00:00;Full Body;1h 5m;Deadlift (Barbell);W;60;5;0;0;;;
            2024-04-20 09:00:00;Full Body;1h 5m;Deadlift (Barbell);1;100;5;0;0;"Belt on; straps";;8
        """.trimIndent()
        assertTrue(StrongImport.recognises(semicolonCsv))
        val parsed = StrongImport.parse(semicolonCsv, zone)
        assertEquals(1, parsed.workouts.size)
        assertEquals(listOf(60.0 to 5, 100.0 to 5), parsed.workouts.single().exercises.single().sets.map { it.weightKg to it.reps })
    }

    @Test
    fun idsAreDeterministicAndUniqueAcrossParses() {
        val again = StrongImport.parse(newerText, zone)
        assertEquals(bundle, again)
        val ids = bundle.workouts.flatMap { w ->
            listOf(w.id) + w.exercises.flatMap { e -> listOf(e.id) + e.sets.map { it.id } }
        }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(
            importId("strong", "workout", "2024-05-10 07:30:00", "Morning Push"),
            workoutAt("2024-05-10T07:30").id,
        )
    }

    @Test
    fun idsSurviveRenumberedWorkouts() {
        // Deleting an earlier workout in Strong renumbers "Workout #"; a later
        // export of the same workouts must give the same ids.
        val renumbered = newerText.lines().joinToString("\n") { line ->
            if (line.startsWith("2,") || line.startsWith("3,")) "9" + line.substring(1) else line
        }
        assertEquals(bundle, StrongImport.parse(renumbered, zone))
    }
}
