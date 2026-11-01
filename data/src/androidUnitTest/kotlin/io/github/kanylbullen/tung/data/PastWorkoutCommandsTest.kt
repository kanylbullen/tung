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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A workout logged after the fact through the app's [AppCommandHandler]:
 * `start_workout` with `started_at`, `finish_workout` with `ended_at`, and
 * `set_workout_time`, each stamping `updatedAt` and `dirty` in Room.
 */
@RunWith(RobolectricTestRunner::class)
class PastWorkoutCommandsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = TungDatabase.buildInMemory(context)
    private val hour = 60L * 60 * 1000
    private var now = 1_761_840_000_000L
    private val extended = ExtendedCatalog({ File("../catalog/extended.json").readText() })
    private val workouts = WorkoutRepository(db, clock = { now })
    private val commands = AppCommandHandler(
        workouts = workouts,
        resolver = AppExerciseResolver(db.exerciseDao(), { mapOf("bench_press" to listOf("Bench press")) }, extended),
    )

    @After
    fun closeDatabase() = db.close()

    private fun done(command: Command): CommandResult.Done {
        val result = runBlocking { commands.execute(command) }
        assertTrue("$command: $result", result is CommandResult.Done)
        return result as CommandResult.Done
    }

    private fun workout(id: String): Workout = checkNotNull(runBlocking { workouts.getWorkout(id) })

    private fun clean() = db.openHelper.writableDatabase.execSQL("UPDATE workout SET dirty = 0")

    @Test
    fun aWorkoutLoggedYesterdayKeepsItsTimesAndIsDirty() {
        val start = now - 24 * hour
        val workoutId = checkNotNull(done(Command.StartWorkout(startedAt = start)).ids[CommandIds.WORKOUT_ID])
        assertEquals(start, workout(workoutId).startedAt)
        val weId = checkNotNull(done(Command.AddExercise(workoutId, ExerciseRef.ByKey("bench_press"), sets = 1)).ids[CommandIds.WORKOUT_EXERCISE_ID])
        val set = runBlocking { workouts.getSets(weId) }.single()
        done(Command.SetReps(set.id, 8))
        done(Command.LogSet(workoutId, set.id))
        done(Command.FinishWorkout(workoutId, endedAt = start + 90 * 60_000L))
        assertEquals(start + 90 * 60_000L, workout(workoutId).endedAt)

        clean()
        now += 1_000L
        done(Command.SetWorkoutTime(workoutId, start - 24 * hour, start - 24 * hour + hour))
        val moved = workout(workoutId)
        assertEquals(start - 24 * hour, moved.startedAt)
        assertEquals(start - 23 * hour, moved.endedAt)
        assertEquals("stamped", now, moved.updatedAt)
        assertTrue("dirty, so it syncs", moved.dirty)
    }

    @Test
    fun theSameTimesAgainWriteNothing() {
        val workoutId = checkNotNull(done(Command.StartWorkout(startedAt = now - 5 * hour)).ids[CommandIds.WORKOUT_ID])
        clean()
        val before = workout(workoutId).updatedAt
        now += 1_000L
        done(Command.SetWorkoutTime(workoutId, now - 1_000L - 5 * hour, null))
        assertEquals(before, workout(workoutId).updatedAt)
        assertEquals(false, workout(workoutId).dirty)
    }
}
