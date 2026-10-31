package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.core.CoreExercise
import io.github.kanylbullen.tung.core.ExtendedExercise
import io.github.kanylbullen.tung.core.ExtendedName
import io.github.kanylbullen.tung.core.StartedBy
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.command.CommandExecutor
import io.github.kanylbullen.tung.core.command.ExerciseCandidate
import io.github.kanylbullen.tung.core.extendedExerciseId
import io.github.kanylbullen.tung.core.importing.ImportBundle
import io.github.kanylbullen.tung.core.importing.ImportChoice
import io.github.kanylbullen.tung.core.importing.ImportedExercise
import io.github.kanylbullen.tung.core.importing.ImportedRoutine
import io.github.kanylbullen.tung.core.importing.ImportedRoutineExercise
import io.github.kanylbullen.tung.core.importing.ImportedSet
import io.github.kanylbullen.tung.core.importing.ImportedWorkout
import io.github.kanylbullen.tung.core.importing.importedCustomExerciseId
import io.github.kanylbullen.tung.core.sync.SyncTable
import io.github.kanylbullen.tung.web.importing.WebImporter
import io.github.kanylbullen.tung.web.importing.undoImport
import io.github.kanylbullen.tung.web.store.TungStore
import io.github.kanylbullen.tung.web.store.WebCommandStore
import io.github.kanylbullen.tung.web.store.WebExerciseResolver
import io.github.kanylbullen.tung.web.store.Write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class WebImporterTest {

    private val bench = catalogExerciseId("bench_press")
    private val zercher = ExtendedExercise("Zercher_Squats", ExtendedName("Zercher Squats"), "legs", listOf("quads"), emptyList(), "barbell", source = "test")

    private val bundle = ImportBundle(
        source = "jefit",
        workouts = listOf(
            ImportedWorkout(
                "w1", 1_000, 2_000,
                listOf(
                    ImportedExercise("we1", "Barbell Bench Press", listOf(ImportedSet("s1", 80.0, 8), ImportedSet("s2", 80.0, 6))),
                    ImportedExercise("we2", "Zercher", listOf(ImportedSet("s3", 60.0, 5))),
                    ImportedExercise("we3", "Odd Thing", listOf(ImportedSet("s4", 10.0, 10))),
                ),
            ),
            ImportedWorkout("w2", 3_000, 4_000, listOf(ImportedExercise("we4", "Skipped One", listOf(ImportedSet("s5", 1.0, 1))))),
        ),
        routines = listOf(ImportedRoutine("r1", "Jefit · Day 1", listOf(ImportedRoutineExercise("re1", "Barbell Bench Press", 3, 8)))),
        customExercises = emptySet(),
        skipped = emptyMap(),
        setTypes = emptyMap(),
    )

    private val choices = mapOf(
        "Barbell Bench Press" to ImportChoice.Use(ExerciseCandidate(bench, listOf("Bench press"), nameKey = "bench_press")),
        "Zercher" to ImportChoice.Use(ExerciseCandidate(extendedExerciseId("Zercher_Squats"), listOf("Zercher Squats"), extendedId = "Zercher_Squats")),
        "Odd Thing" to ImportChoice.CreateCustom,
        "Skipped One" to ImportChoice.Skip,
    )

    private class Rig(extended: List<ExtendedExercise>) {
        val store = TungStore().apply { seedCatalogue(listOf(CoreExercise("bench_press", mapOf("en" to "Bench press"), "chest", "barbell")), ArrayList()) }
        val persisted = ArrayList<List<Write>>()
        var now = 10_000L
        val importer = WebImporter(store, persist = { persisted += it }, extended = { extended }, clock = { now++ })
        val commands = CommandExecutor(WebCommandStore(store, persist = { persisted += it }, clock = { now++ }), WebExerciseResolver(store, { emptyMap() }), StartedBy.WEB)
    }

    @Test
    fun anImportIsWrittenOnceDirtyAndInOneJournal() = runTest {
        val rig = Rig(listOf(zercher))
        val result = rig.importer.apply(bundle, choices)
        assertEquals(1, result.workoutsAdded, "the workout with every exercise skipped is not written")
        assertEquals(4, result.setsAdded)
        assertEquals(listOf("r1"), result.routineIds)
        assertEquals(1, rig.persisted.size, "one IndexedDB transaction")
        val workout = assertNotNull(rig.store.workouts.get("w1"))
        assertEquals("jefit", workout.importSource)
        assertEquals(result.batchId, workout.importBatchId)
        assertTrue(rig.store.workouts.rows.getValue("w1").dirty)
        assertNotNull(rig.store.exercises.get(extendedExerciseId("Zercher_Squats")), "the extended entry got its row")
        assertEquals(true, rig.store.exercises.get(importedCustomExerciseId("jefit", "Odd Thing"))?.isCustom)
        assertEquals(8, rig.store.routineExercises.get("re1")?.targetReps)

        val again = rig.importer.apply(bundle, choices)
        assertEquals(0, again.workoutsAdded)
        assertEquals(1, again.workoutsAlreadyThere)
        assertEquals(emptyList(), again.routineIds)
    }

    @Test
    fun undoRemovesTheBatchAndItsRoutinesAndAReImportBringsThemBack() = runTest {
        val rig = Rig(listOf(zercher))
        val result = rig.importer.apply(bundle, choices)
        assertEquals(1, undoImport(rig.store, rig.commands, result.batchId, result.routineIds))
        assertNull(rig.store.workouts.get("w1"))
        assertNull(rig.store.routines.get("r1"))
        assertNotNull(rig.store.tombstones[SyncTable.WORKOUTS to "w1"])
        val again = rig.importer.apply(bundle, choices)
        assertEquals(1, again.workoutsAdded)
        assertNull(rig.store.tombstones[SyncTable.WORKOUTS to "w1"], "the tombstone goes, so sync brings the workout back")
    }
}
