package io.github.kanylbullen.tung.core.importing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlinx.datetime.TimeZone

/**
 * A local end-to-end check against a real Jefit export, which is personal
 * data and never in the repository: skipped unless `TUNG_JEFIT_EXPORT` names
 * the file. Run it with
 * `TUNG_JEFIT_EXPORT=/path/to/export.csv ./gradlew :core:test --tests '*JefitRealExportTest' --rerun --no-daemon`;
 * it prints the counts.
 */
class JefitRealExportTest {

    @Test
    fun aRealExportIsReadCompletely() = runBlocking {
        val path = System.getenv("TUNG_JEFIT_EXPORT")
        assumeTrue("TUNG_JEFIT_EXPORT not set", !path.isNullOrBlank() && File(path).isFile)
        val text = File(path).readText()
        val bundle = JefitImport.parse(text, TimeZone.of("Europe/Stockholm"))
        val sessions = splitSections(text).getValue("WORKOUT SESSIONS").records("_id").size
        val matches = resolveImportNames(bundle.importExerciseNames(), ImportTestFiles.jefitAliases, ImportTestFiles.resolver())
        val unmatched = matches.filterValues { it is ImportMatch.Unmatched }.keys.sorted()
        println(
            """
            Jefit export: $sessions sessions → ${bundle.workouts.size} workouts, ${bundle.setCount} sets, ${bundle.routines.size} routines
            dates: ${bundle.dateRange?.let { java.time.Instant.ofEpochMilli(it.first).toString() + " … " + java.time.Instant.ofEpochMilli(it.last) }}
            set rows: ${bundle.setRows}
            set types imported as ordinary sets: ${bundle.setTypes}
            skipped: ${bundle.skipped}
            names: ${matches.size}, via alias ${matches.values.count { it is ImportMatch.Matched && it.viaAlias }}, by name ${matches.values.count { it is ImportMatch.Matched && !it.viaAlias }}
            unmatched: $unmatched
            custom exercises: ${bundle.customExercises}
            """.trimIndent(),
        )
        // Every set row is used or skipped with a reason.
        assertEquals(bundle.setRows.read, bundle.setRows.used + bundle.setRows.skipped)
        // Every session is a workout or skipped with a reason (loose logs' days add workouts of their own).
        val sessionWorkouts = bundle.workouts.count { w ->
            splitSections(text).getValue("WORKOUT SESSIONS").records("_id").any { importId("jefit", "workout", userId(text), "session", it["_id"].trim()) == w.id }
        }
        val skippedSessions = (bundle.skipped[SkipReason.SESSION_WITHOUT_EXERCISES] ?: 0) + (bundle.skipped[SkipReason.SESSION_WITHOUT_TIME] ?: 0)
        assertEquals(sessions, sessionWorkouts + skippedSessions)
        // Cardio is skipped, never shown for review.
        assertEquals(false, bundle.importExerciseNames().any { it.equals("swimming", ignoreCase = true) })
    }

    private fun userId(text: String) = splitSections(text)["SETTING"]?.records("USERID")?.firstOrNull()?.get("USERID")?.trim() ?: "0"
}
