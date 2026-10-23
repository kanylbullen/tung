package io.github.kanylbullen.tung.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kanylbullen.tung.core.ExtendedCatalog
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.ExerciseRef
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * `add_exercise` with and without a set count, and `add_set` with a count
 * (issue #55), through the app's [AppCommandHandler] over a real Room
 * database: how many sets are created and what they are pre-filled with.
 */
@RunWith(RobolectricTestRunner::class)
class AddExerciseCommandsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = TungDatabase.buildInMemory(context)
    private var now = 1_000L
    private val extended = ExtendedCatalog({ File("../catalog/extended.json").readText() })
    private val workouts = WorkoutRepository(db, clock = { now })
    private val commands = AppCommandHandler(
        db = db,
        workouts = workouts,
        exercises = ExerciseRepository(db, clock = { now }),
        resolver = AppExerciseResolver(db.exerciseDao(), { mapOf("bench_press" to listOf("Bench press", "Bänkpress")) }, extended),
        extendedCatalog = extended,
    )
    private val bench = ExerciseRef.ByKey("bench_press")

    @After
    fun closeDatabase() = db.close()

    private fun run(command: Command): CommandResult = runBlocking { commands.execute(command) }

    private fun done(command: Command): CommandResult.Done {
        val result = run(command)
        assertTrue("$command: $result", result is CommandResult.Done)
        return result as CommandResult.Done
    }

    private fun Command.id(key: String): String = checkNotNull(done(this).ids[key]) { "$this returned no $key" }

    private fun sets(workoutExerciseId: String): List<SetEntry> =
        runBlocking { workouts.getSets(workoutExerciseId) }.sortedBy { it.position }

    private fun values(workoutExerciseId: String) = sets(workoutExerciseId).map { it.weightKg to it.reps }

    private fun startWorkout(): String {
        now += 3_600_000L
        return Command.StartWorkout().id(CommandIds.WORKOUT_ID)
    }

    /** A finished workout with bench press logged as [logged], set by set. */
    private fun benchSession(logged: List<Pair<Double, Int>>) {
        val workoutId = startWorkout()
        val weId = Command.AddExercise(workoutId, bench, sets = logged.size).id(CommandIds.WORKOUT_EXERCISE_ID)
        sets(weId).zip(logged).forEach { (set, value) ->
            done(Command.SetWeight(set.id, value.first))
            done(Command.SetReps(set.id, value.second))
            done(Command.TickSet(set.id, true))
        }
        now += 60_000L
        done(Command.FinishWorkout(workoutId))
    }

    @Test
    fun withoutHistoryAnAddedExerciseHasOneEmptySet() {
        val workoutId = startWorkout()
        val weId = Command.AddExercise(workoutId, bench).id(CommandIds.WORKOUT_EXERCISE_ID)
        assertEquals(listOf(0.0 to 0), values(weId))
        assertTrue(sets(weId).none { it.completed })
    }

    @Test
    fun withHistoryItGetsLastSessionsSetsAtTheirPositions() {
        benchSession(listOf(80.0 to 8, 85.0 to 6, 85.0 to 5))
        val workoutId = startWorkout()
        val weId = Command.AddExercise(workoutId, bench).id(CommandIds.WORKOUT_EXERCISE_ID)
        assertEquals(listOf(80.0 to 8, 85.0 to 6, 85.0 to 5), values(weId))
        assertEquals(listOf(0, 1, 2), sets(weId).map { it.position })
        assertTrue("nothing is ticked", sets(weId).none { it.completed })
    }

    @Test
    fun aSetCountWinsOverHistoryAndPastItsEndRepeatsTheSetBefore() {
        benchSession(listOf(80.0 to 8, 85.0 to 6))
        val workoutId = startWorkout()
        val four = Command.AddExercise(workoutId, bench, sets = 4).id(CommandIds.WORKOUT_EXERCISE_ID)
        assertEquals(listOf(80.0 to 8, 85.0 to 6, 85.0 to 6, 85.0 to 6), values(four))
        val one = Command.AddExercise(workoutId, bench, sets = 1).id(CommandIds.WORKOUT_EXERCISE_ID)
        assertEquals(listOf(80.0 to 8), values(one))
        val squat = Command.AddExercise(workoutId, ExerciseRef.ByKey("back_squat"), sets = 4).id(CommandIds.WORKOUT_EXERCISE_ID)
        assertEquals("no history: 0 × 0 each", List(4) { 0.0 to 0 }, values(squat))
    }

    @Test
    fun aRoutineStartIsUnaffected() {
        benchSession(listOf(80.0 to 8, 85.0 to 6, 85.0 to 5))
        val routineId = checkNotNull(
            done(
                Command.SaveRoutinePlan(
                    io.github.kanylbullen.tung.core.command.RoutinePlan(
                        "Push",
                        listOf(io.github.kanylbullen.tung.core.command.RoutinePlanExercise(bench, 2, 8)),
                    ),
                ),
            ).ids[CommandIds.ROUTINE_ID],
        )
        now += 3_600_000L
        val workoutId = Command.StartRoutine(routineId).id(CommandIds.WORKOUT_ID)
        val weId = runBlocking { workouts.getExercisesWithSets(workoutId) }.single().workoutExercise.id
        assertEquals("the routine's target sets, not last session's three", listOf(80.0 to 8, 85.0 to 6), values(weId))
    }

    @Test
    fun addSetWithACountAddsThatManyCopies() {
        val workoutId = startWorkout()
        val weId = Command.AddExercise(workoutId, bench).id(CommandIds.WORKOUT_EXERCISE_ID)
        val first = sets(weId).single()
        done(Command.SetWeight(first.id, 60.0))
        done(Command.SetReps(first.id, 8))
        val added = Command.AddSet(weId, count = 3).id(CommandIds.SET_ID)
        val all = sets(weId)
        assertEquals(List(4) { 60.0 to 8 }, values(weId))
        assertEquals("the first added set is returned", all[1].id, added)
        assertEquals(listOf(0, 1, 2, 3), all.map { it.position })
        Command.AddSet(weId).id(CommandIds.SET_ID)
        assertEquals("without a count, one", 5, sets(weId).size)
        assertTrue(run(Command.AddSet(weId, count = 0)) is CommandResult.Rejected)
        assertTrue(run(Command.AddExercise(workoutId, bench, sets = 21)) is CommandResult.Rejected)
        assertEquals("a rejected command writes nothing", 5, sets(weId).size)
        assertEquals(catalogExerciseId("bench_press"), runBlocking { workouts.getExercisesWithSets(workoutId) }.single().exercise.id)
    }
}
