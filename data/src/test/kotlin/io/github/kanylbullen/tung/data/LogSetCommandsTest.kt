package io.github.kanylbullen.tung.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kanylbullen.tung.core.ExtendedCatalog
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.ExerciseRef
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * `log_set` through the app's [AppCommandHandler] (issue #64): it only ticks
 * the set; `add_next = true` keeps the old behaviour, adding the next set
 * after the exercise's last, never past a routine's target sets.
 */
@RunWith(RobolectricTestRunner::class)
class LogSetCommandsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = TungDatabase.buildInMemory(context)
    private var now = 1_000L
    private val extended = ExtendedCatalog({ File("../catalog/extended.json").readText() })
    private val workouts = WorkoutRepository(db, clock = { now })
    private val commands = AppCommandHandler(
        workouts = workouts,
        resolver = AppExerciseResolver(db.exerciseDao(), { mapOf("bench_press" to listOf("Bench press")) }, extended),
    )
    private val bench = ExerciseRef.ByKey("bench_press")

    @After
    fun closeDatabase() = db.close()

    private fun done(command: Command): CommandResult.Done {
        val result = runBlocking { commands.execute(command) }
        assertTrue("$command: $result", result is CommandResult.Done)
        return result as CommandResult.Done
    }

    private fun Command.id(key: String): String = checkNotNull(done(this).ids[key]) { "$this returned no $key" }

    private fun sets(workoutExerciseId: String): List<SetEntry> =
        runBlocking { workouts.getSets(workoutExerciseId) }.sortedBy { it.position }

    /** A workout with bench press: [count] sets of 80 × 8, none ticked. */
    private fun benchWorkout(count: Int): Pair<String, String> {
        val workoutId = Command.StartWorkout().id(CommandIds.WORKOUT_ID)
        val weId = Command.AddExercise(workoutId, bench, sets = count).id(CommandIds.WORKOUT_EXERCISE_ID)
        sets(weId).forEach {
            done(Command.SetWeight(it.id, 80.0))
            done(Command.SetReps(it.id, 8))
        }
        return workoutId to weId
    }

    @Test
    fun logSetOnlyTicksEvenTheLastSet() {
        val (workoutId, weId) = benchWorkout(2)
        val (first, second) = sets(weId)
        val one = done(Command.LogSet(workoutId, first.id))
        assertEquals(first.id, one.ids[CommandIds.SET_ID])
        val two = done(Command.LogSet(workoutId, second.id))
        assertNull("no set is added after the last", two.ids[CommandIds.ADDED_SET_ID])
        assertEquals(listOf(true, true), sets(weId).map { it.completed })
    }

    @Test
    fun logSetWithoutASetIdTicksTheCurrentSetAndNothingElse() {
        val (workoutId, weId) = benchWorkout(1)
        done(Command.LogSet(workoutId))
        assertEquals(listOf(true), sets(weId).map { it.completed })
    }

    @Test
    fun addNextAddsACopyAfterTheLastSet() {
        val (workoutId, weId) = benchWorkout(2)
        val (first, second) = sets(weId)
        assertNull(done(Command.LogSet(workoutId, first.id, addNext = true)).ids[CommandIds.ADDED_SET_ID])
        val added = Command.LogSet(workoutId, second.id, addNext = true).id(CommandIds.ADDED_SET_ID)
        val after = sets(weId)
        assertEquals(3, after.size)
        assertEquals(added, after.last().id)
        assertEquals(80.0 to 8, after.last().weightKg to after.last().reps)
        assertEquals(false, after.last().completed)
    }

    @Test
    fun addNextRespectsTheRoutinesTargetSets() {
        val routineId = Command.CreateRoutine("Push").id(CommandIds.ROUTINE_ID)
        done(Command.AddRoutineExercise(routineId, bench, targetSets = 2))
        val workoutId = Command.StartRoutine(routineId).id(CommandIds.WORKOUT_ID)
        val weId = runBlocking { workouts.getExercisesWithSets(workoutId) }.single().workoutExercise.id
        val (first, second) = sets(weId)
        done(Command.SetReps(first.id, 8))
        done(Command.SetReps(second.id, 8))
        done(Command.LogSet(workoutId, first.id, addNext = true))
        assertNull(
            "the routine's second set of two adds no third",
            done(Command.LogSet(workoutId, second.id, addNext = true)).ids[CommandIds.ADDED_SET_ID],
        )
        assertEquals(2, sets(weId).size)
    }

    @Test
    fun addSetAfterTheLastLoggedSetCopiesIt() {
        val (workoutId, weId) = benchWorkout(1)
        done(Command.LogSet(workoutId, sets(weId).single().id))
        val added = Command.AddSet(weId).id(CommandIds.SET_ID)
        val after = sets(weId)
        assertEquals(listOf(true, false), after.map { it.completed })
        assertEquals(added, after.last().id)
        assertEquals(80.0 to 8, after.last().weightKg to after.last().reps)
    }
}
