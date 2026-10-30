package io.github.kanylbullen.tung.importing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kanylbullen.tung.core.CoreCatalog
import io.github.kanylbullen.tung.core.ExtendedCatalog
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.importing.ImportAliases
import io.github.kanylbullen.tung.core.importing.ImportChoice
import io.github.kanylbullen.tung.core.importing.importedCustomExerciseId
import io.github.kanylbullen.tung.core.sync.SyncTable
import io.github.kanylbullen.tung.data.AppCommandHandler
import io.github.kanylbullen.tung.data.AppExerciseResolver
import io.github.kanylbullen.tung.data.ExerciseRepository
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.TungDatabase
import io.github.kanylbullen.tung.data.buildInMemory
import io.github.kanylbullen.tung.data.WorkoutRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlinx.datetime.TimeZone

/**
 * The [Importer] over a real Room database with the synthetic Jefit export
 * (`core/src/jvmTest/resources/import/jefit/`): one transaction, idempotent on
 * the deterministic ids, stamped and dirty like every write, and undone one
 * batch at a time through the ordinary deletes.
 */
@RunWith(RobolectricTestRunner::class)
class ImporterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = TungDatabase.buildInMemory(context)
    private var now = 5_000_000L
    private val extended = ExtendedCatalog({ File("../catalog/extended.json").readText() })
    private val names = CoreCatalog.parse(File("../catalog/core.json").readText()).associate { it.key to it.name.values.toList() }
    private val workouts = WorkoutRepository(db, clock = { now })
    private val exercises = ExerciseRepository(db, clock = { now })
    private val routines = RoutineRepository(db, clock = { now })
    private val resolver = AppExerciseResolver(db.exerciseDao(), { names }, extended)
    private val service = ImportService(
        importer = Importer(db, workouts, exercises, routines, extended, clock = { now }),
        resolver = resolver,
        aliases = { ImportAliases.parse(File("../catalog/aliases/jefit.json").readText()) },
        zone = { TimeZone.of("Europe/Stockholm") },
    )
    private val commands = AppCommandHandler(workouts, resolver)
    private val text = File("../core/src/jvmTest/resources/import/jefit/export-synthetic.csv").readText()

    @After
    fun closeDatabase() = db.close()

    private fun preview(): ImportPreview = runBlocking { (service.read(text) as ImportRead.Ready).preview }

    private fun importWith(extra: Map<String, ImportChoice>): ImportResult = runBlocking {
        val p = preview()
        service.apply(p.bundle, p.matchedChoices + extra)
    }

    private fun finishedWorkouts() = runBlocking { workouts.observeFinishedWorkouts().first() }

    @Test
    fun theReviewHasTheOneUnmatchedName() {
        val p = preview()
        assertEquals(listOf("Heel Walking"), p.unmatched)
        assertEquals(4, p.bundle.workouts.size)
    }

    @Test
    fun importWritesWorkoutsSetsAndRoutinesStampedAndDirty() = runBlocking {
        val result = importWith(mapOf("Heel Walking" to ImportChoice.Skip))
        assertEquals(4, result.workoutsAdded)
        assertEquals(11, result.setsAdded)
        assertEquals(3, result.routineIds.size)
        assertEquals(0, result.customExercisesCreated)

        val stored = db.syncDao().dirtyWorkouts()
        assertEquals(4, stored.size)
        stored.forEach {
            assertEquals("jefit", it.importSource)
            assertEquals(result.batchId, it.importBatchId)
            assertEquals(now, it.updatedAt)
            assertTrue(it.dirty)
            assertTrue(it.endedAt != null)
        }
        assertEquals(11, db.syncDao().dirtySets().size)
        assertTrue(db.syncDao().dirtySets().all { it.completed && it.reps > 0 && it.updatedAt == now })
        assertEquals(6, db.syncDao().dirtyWorkoutExercises().size)
        assertEquals(3, db.syncDao().dirtyRoutines().size)
        // Monday lost Heel Walking with the skip; its deadlift stays.
        assertEquals(4, db.syncDao().dirtyRoutineExercises().size)

        // History and "last session" read imported workouts like any other.
        val deadlift = catalogExerciseId("deadlift")
        assertEquals(listOf(60.0 to 5, 70.0 to 5, 80.0 to 5), db.workoutDao().previousSessionSets(deadlift, "none").map { it.weightKg to it.reps })
        assertEquals(4, finishedWorkouts().size)
    }

    @Test
    fun aSecondImportAddsNothing() = runBlocking {
        importWith(mapOf("Heel Walking" to ImportChoice.CreateCustom))
        val before = Triple(db.syncDao().dirtyWorkouts().size, db.syncDao().dirtySets().size, db.syncDao().dirtyRoutines().size)
        now += 60_000
        val again = importWith(emptyMap())
        assertEquals(0, again.workoutsAdded)
        assertEquals(0, again.setsAdded)
        assertEquals(emptyList<String>(), again.routineIds)
        assertEquals(4, again.workoutsAlreadyThere)
        assertEquals(0, again.customExercisesCreated)
        assertEquals(before, Triple(db.syncDao().dirtyWorkouts().size, db.syncDao().dirtySets().size, db.syncDao().dirtyRoutines().size))
        // The custom exercise the first import made is found by name now: nothing left to review.
        assertEquals(emptyList<String>(), preview().unmatched)
        assertEquals("Heel Walking", db.exerciseDao().getById(importedCustomExerciseId("jefit", "Heel Walking"))?.name)
    }

    @Test
    fun undoRemovesTheBatchWithTombstonesAndLeavesOtherWorkouts() = runBlocking {
        // A workout logged in Tung.
        val own = (commands.execute(Command.StartWorkout()) as CommandResult.Done).ids.getValue(CommandIds.WORKOUT_ID)
        val we = (commands.execute(Command.AddExercise(own, ExerciseRef.ByKey("bench_press"), sets = 1)) as CommandResult.Done).ids.getValue(CommandIds.WORKOUT_EXERCISE_ID)
        val set = workouts.getSets(we).single()
        commands.execute(Command.SetReps(set.id, 5))
        commands.execute(Command.TickSet(set.id, true))
        commands.execute(Command.FinishWorkout(own))

        val result = importWith(mapOf("Heel Walking" to ImportChoice.Skip))
        assertEquals(5, finishedWorkouts().size)
        val importedIds = db.workoutDao().importBatchWorkoutIds(result.batchId)

        now += 60_000
        assertEquals(4, service.undo(result))
        assertEquals(listOf(own), finishedWorkouts().map { it.id })
        assertEquals(0, routines.observeRoutines().first().size)
        val tombstones = db.tombstoneDao().byIds(SyncTable.WORKOUTS.key, importedIds)
        assertEquals(importedIds.toSet(), tombstones.map { it.id }.toSet())
        assertTrue(tombstones.all { it.dirty && it.deletedAt == now })
        assertEquals(11, db.tombstoneDao().dirty().count { it.tableName == SyncTable.SETS.key })
        assertEquals(3, db.tombstoneDao().dirty().count { it.tableName == SyncTable.ROUTINES.key })

        // Importing again after the undo brings the rows back and drops their tombstones.
        now += 60_000
        assertEquals(4, importWith(mapOf("Heel Walking" to ImportChoice.Skip)).workoutsAdded)
        assertEquals(emptyList<Any>(), db.tombstoneDao().byIds(SyncTable.WORKOUTS.key, importedIds))
        assertEquals(0, db.tombstoneDao().dirty().count { it.tableName == SyncTable.SETS.key })
    }

    @Test
    fun aWorkoutWithEveryExerciseSkippedIsNotWritten() = runBlocking {
        val p = preview()
        val choices = p.bundle.workouts.flatMap { w -> w.exercises.map { it.name } }.associateWith { ImportChoice.Skip as ImportChoice } +
            mapOf("Barbell Bent Over Row" to (p.matchedChoices.getValue("Barbell Bent Over Row")))
        val result = service.apply(p.bundle, choices)
        assertEquals(1, result.workoutsAdded)
        assertEquals(3, result.workoutsEmpty)
        assertNull(db.workoutDao().getWorkout(p.bundle.workouts.first().id))
    }

    @Test
    fun anExtendedEntryGetsItsRowAndACustomOneIsCreatedOnce() = runBlocking {
        val p = preview()
        val squat = p.bundle.workouts.first().exercises.first()
        val dumbbellSquat = extended.all().single { it.id == "Dumbbell_Squat" }
        val choices = p.matchedChoices + mapOf(
            squat.name to ImportChoice.Use(io.github.kanylbullen.tung.core.command.ExerciseCandidate(io.github.kanylbullen.tung.core.extendedExerciseId(dumbbellSquat.id), listOf(dumbbellSquat.displayName), extendedId = dumbbellSquat.id)),
            "Heel Walking" to ImportChoice.CreateCustom,
        )
        val result = service.apply(p.bundle, choices)
        assertEquals(1, result.customExercisesCreated)
        assertEquals("Dumbbell Squat", db.exerciseDao().getById(io.github.kanylbullen.tung.core.extendedExerciseId("Dumbbell_Squat"))?.name)
        val custom = db.exerciseDao().getById(importedCustomExerciseId("jefit", "Heel Walking"))
        assertEquals(true, custom?.isCustom)
        assertEquals(true, custom?.dirty)
    }
}
