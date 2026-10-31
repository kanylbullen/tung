package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.core.CoreExercise
import io.github.kanylbullen.tung.core.ExtendedExercise
import io.github.kanylbullen.tung.core.ExtendedName
import io.github.kanylbullen.tung.core.extendedExerciseId
import io.github.kanylbullen.tung.core.StartedBy
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandExecutor
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.sync.SyncTable
import io.github.kanylbullen.tung.web.store.IndexedDbStore
import io.github.kanylbullen.tung.web.store.TungStore
import io.github.kanylbullen.tung.web.store.WebCommandStore
import io.github.kanylbullen.tung.web.store.WebExerciseResolver
import io.github.kanylbullen.tung.web.store.Write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Core's command executor over the browser's store: what it writes, what it stamps, and that a refused command leaves nothing. */
class WebCommandsTest {

    private class Rig {
        val store = TungStore()
        val persisted = ArrayList<List<Write>>()
        var now = 1_000L
        val names = mapOf(
            "bench_press" to mapOf("en" to "Bench press", "sv" to "Bänkpress"),
            "back_squat" to mapOf("en" to "Back squat", "sv" to "Knäböj"),
        )
        val executor = CommandExecutor(
            WebCommandStore(store, persist = { persisted += it }, clock = { now++ }),
            WebExerciseResolver(store, { names }) {
                listOf(ExtendedExercise("Zercher_Squats", ExtendedName("Zercher Squats"), "legs", listOf("quads"), emptyList(), "barbell", source = "test"))
            },
            startedBy = StartedBy.WEB,
        )

        init {
            store.seedCatalogue(
                listOf(
                    CoreExercise("bench_press", names.getValue("bench_press"), "chest", "barbell"),
                    CoreExercise("back_squat", names.getValue("back_squat"), "legs", "barbell"),
                ),
                ArrayList(),
            )
        }

        suspend fun run(command: Command): CommandResult.Done = assertIs(executor.execute(command))
    }

    @Test
    fun anExtendedEntryGetsItsRowTheFirstTimeACommandNamesIt() = runTest {
        val rig = Rig()
        val workoutId = assertNotNull(rig.run(Command.StartWorkout()).ids[CommandIds.WORKOUT_ID])
        rig.run(Command.AddExercise(workoutId, ExerciseRef.Extended("Zercher_Squats")))
        val row = assertNotNull(rig.store.exercises.get(extendedExerciseId("Zercher_Squats")))
        assertEquals("Zercher Squats", row.name)
        assertEquals("legs", row.muscleGroup)
    }

    @Test
    fun aWholeWorkoutIsWrittenStampedAndDirty() = runTest {
        val rig = Rig()
        val workoutId = assertNotNull(rig.run(Command.StartWorkout()).ids[CommandIds.WORKOUT_ID])
        val workout = assertNotNull(rig.store.workouts.get(workoutId))
        assertEquals(StartedBy.WEB, workout.startedBy)
        assertTrue(rig.store.workouts.rows.getValue(workoutId).dirty)

        // A Swedish name resolves through the catalogue's names.
        rig.run(Command.AddExercise(workoutId, ExerciseRef.ByName("Bänkpress"), sets = 2))
        val exercise = rig.store.workoutExercises.rows.values.single().row
        assertEquals(catalogExerciseId("bench_press"), exercise.exerciseId)
        val sets = rig.store.sets.rows.values.map { it.row }.sortedBy { it.position }
        assertEquals(2, sets.size)

        rig.run(Command.SetWeight(sets[0].id, 80.0))
        rig.run(Command.SetReps(sets[0].id, 8))
        rig.run(Command.LogSet(workoutId, sets[0].id))
        rig.run(Command.FinishWorkout(workoutId))

        val finished = assertNotNull(rig.store.workouts.get(workoutId))
        assertNotNull(finished.endedAt)
        assertEquals(listOf(sets[0].id), rig.store.sets.rows.keys.toList(), "finish drops the set that was never logged")
        val logged = rig.store.sets.get(sets[0].id)
        assertEquals(80.0, logged?.weightKg)
        assertEquals(true, logged?.completed)
        assertTrue(rig.store.tombstones.getValue(SyncTable.SETS to sets[1].id).dirty, "the dropped set leaves a tombstone for sync")
        assertEquals(6, rig.persisted.size, "one journal per command: start, add, weight, reps, log, finish")
        assertTrue(rig.store.localChanges().dirty.sets.isNotEmpty())
    }

    @Test
    fun theNextSessionIsPrefilledFromTheLastOne() = runTest {
        val rig = Rig()
        val first = assertNotNull(rig.run(Command.StartWorkout()).ids[CommandIds.WORKOUT_ID])
        rig.run(Command.AddExercise(first, ExerciseRef.ByKey("back_squat"), sets = 1))
        val set = rig.store.sets.rows.values.single().row
        rig.run(Command.SetWeight(set.id, 100.0))
        rig.run(Command.SetReps(set.id, 5))
        rig.run(Command.LogSet(first, set.id))
        rig.run(Command.FinishWorkout(first))

        // A second finished session, heavier: the latest one is what the next pre-fills from.
        val second = assertNotNull(rig.run(Command.StartWorkout()).ids[CommandIds.WORKOUT_ID])
        val secondWe = assertNotNull(rig.run(Command.AddExercise(second, ExerciseRef.ByKey("back_squat"))).ids[CommandIds.WORKOUT_EXERCISE_ID])
        val secondSet = rig.store.sets.rows.values.map { it.row }.single { it.workoutExerciseId == secondWe }
        assertEquals(100.0, secondSet.weightKg)
        assertEquals(5, secondSet.reps)
        assertEquals(false, secondSet.completed)
        rig.run(Command.SetWeight(secondSet.id, 105.0))
        rig.run(Command.LogSet(second, secondSet.id))
        rig.run(Command.FinishWorkout(second))

        val third = assertNotNull(rig.run(Command.StartWorkout()).ids[CommandIds.WORKOUT_ID])
        val thirdWe = assertNotNull(rig.run(Command.AddExercise(third, ExerciseRef.ByKey("back_squat"))).ids[CommandIds.WORKOUT_EXERCISE_ID])
        assertEquals(105.0, rig.store.sets.rows.values.map { it.row }.single { it.workoutExerciseId == thirdWe }.weightKg)
    }

    @Test
    fun aRefusedCommandLeavesNothing() = runTest {
        val rig = Rig()
        val workoutId = assertNotNull(rig.run(Command.StartWorkout()).ids[CommandIds.WORKOUT_ID])
        val before = rig.persisted.size
        val unknown = rig.executor.execute(Command.AddExercise(workoutId, ExerciseRef.ByName("Underwater basket weaving")))
        assertIs<CommandResult.NeedsAnswers>(unknown)
        val refused = rig.executor.execute(Command.LogSet("no-such-workout"))
        assertIs<CommandResult.Rejected>(refused)
        assertEquals(before, rig.persisted.size)
        assertTrue(rig.store.workoutExercises.rows.isEmpty())
    }

    @Test
    fun deletingAWorkoutTombstonesEveryRow() = runTest {
        val rig = Rig()
        val workoutId = assertNotNull(rig.run(Command.StartWorkout()).ids[CommandIds.WORKOUT_ID])
        rig.run(Command.AddExercise(workoutId, ExerciseRef.ByKey("bench_press"), sets = 2))
        rig.run(Command.DeleteWorkout(workoutId))
        assertNull(rig.store.workouts.get(workoutId))
        assertTrue(rig.store.sets.rows.isEmpty() && rig.store.workoutExercises.rows.isEmpty())
        val tombstones = rig.store.localChanges().tombstones
        assertEquals(1, tombstones.workouts.size)
        assertEquals(1, tombstones.workoutExercises.size)
        assertEquals(2, tombstones.sets.size)
    }

    @Test
    fun aCommandsWritesReachIndexedDb() = runTest {
        val name = "tung-commands-${kotlin.js.Date.now().toLong()}"
        IndexedDbStore.delete(name)
        val db = IndexedDbStore.open(name)
        try {
            val store = TungStore()
            val executor = CommandExecutor(
                WebCommandStore(store, persist = { db.write(it) }, clock = { 5_000L }),
                WebExerciseResolver(store, { emptyMap() }),
                startedBy = StartedBy.WEB,
            )
            val workoutId = assertNotNull(assertIs<CommandResult.Done>(executor.execute(Command.StartWorkout())).ids[CommandIds.WORKOUT_ID])
            val reloaded = TungStore()
            db.loadInto(reloaded)
            assertEquals(5_000L, reloaded.workouts.get(workoutId)?.updatedAt)
            assertTrue(reloaded.workouts.rows.getValue(workoutId).dirty)
        } finally {
            db.close()
            IndexedDbStore.delete(name)
        }
    }

    @Test
    fun aFailedWriteUndoesTheCommandInMemory() = runTest {
        val store = TungStore()
        val executor = CommandExecutor(
            WebCommandStore(store, persist = { error("quota exceeded") }, clock = { 1L }),
            WebExerciseResolver(store, { emptyMap() }),
            startedBy = StartedBy.WEB,
        )
        val thrown = runCatching { executor.execute(Command.StartWorkout()) }
        assertTrue(thrown.isFailure)
        assertTrue(store.workouts.rows.isEmpty())
    }
}
