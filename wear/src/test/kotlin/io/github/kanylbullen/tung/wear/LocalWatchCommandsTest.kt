package io.github.kanylbullen.tung.wear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.command.RoutinePlan
import io.github.kanylbullen.tung.core.command.RoutinePlanExercise
import io.github.kanylbullen.tung.core.command.id
import io.github.kanylbullen.tung.core.wear.WatchCommand
import io.github.kanylbullen.tung.core.wear.WatchLastSet
import io.github.kanylbullen.tung.core.wear.WatchWorkoutState
import io.github.kanylbullen.tung.data.TungContainer
import io.github.kanylbullen.tung.data.TungDatabase
import io.github.kanylbullen.tung.sync.MemoryTokenStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A standalone watch: its own database through the shared data layer, taps
 * applied by [LocalWatchCommands] through the shared CommandHandler, the
 * state built by the same `WatchStateSource` the phone publishes with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class LocalWatchCommandsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = TungDatabase.buildInMemory(context)
    private val container = TungContainer(context, createDatabase = { db }, createTokenStore = { MemoryTokenStore("tung_watch") })
    private val backend = LocalWatchBackend(container, context)
    private var commandCount = 0

    @After
    fun close() = db.close()

    private fun send(make: (String, Long) -> WatchCommand): Unit = runBlocking {
        backend.send(make("cmd-${++commandCount}", 1_000L))
    }

    private fun state(): WatchWorkoutState = runBlocking { checkNotNull(backend.states().first()) }

    /** A routine of bench press, two sets of eight, saved through the CommandHandler as the phone would. */
    private fun benchRoutine(): String = runBlocking {
        val plan = RoutinePlan("Push", listOf(RoutinePlanExercise(ExerciseRef.ByKey("bench_press"), targetSets = 2, targetReps = 8)))
        checkNotNull(container.commands.execute(Command.SaveRoutinePlan(plan)).id(CommandIds.ROUTINE_ID))
    }

    @Test
    fun aRoutineIsLoggedOnTheWatchByThePhonesRules() {
        val routineId = benchRoutine()
        assertEquals(listOf(routineId), state().routines.map { it.id })

        send { id, at -> WatchCommand.StartRoutine(routineId, id, at) }
        val started = state()
        assertTrue(started.isActive)
        val bench = started.exercises.single()
        assertEquals("Bench press", bench.name)
        assertEquals(listOf(8, 8), bench.sets.map { it.reps })
        assertEquals(bench.sets.first().setId, started.currentSetId)

        // One workout in progress: starting again keeps it.
        send { id, at -> WatchCommand.StartWorkout(id, at) }
        assertEquals(started.workoutId, state().workoutId)

        val (first, second) = bench.sets
        send { id, at -> WatchCommand.SetWeight(first.setId, 80.0, id, at) }
        send { id, at -> WatchCommand.SetWeight(second.setId, 80.0, id, at) }
        // The command's validation: no 1000 kg.
        send { id, at -> WatchCommand.SetWeight(second.setId, 1_000.0, id, at) }
        assertEquals(listOf(80.0, 80.0), state().exercises.single().sets.map { it.weightKg })

        send { id, at -> WatchCommand.LogSet(first.setId, id, at) }
        val afterFirst = state()
        assertEquals(second.setId, afterFirst.currentSetId)
        assertNotNull("the rest runs on the watch", afterFirst.rest)
        runBlocking { assertNotNull(container.restTimer.stored(checkNotNull(started.workoutId))) }

        // The routine's target: the second of two adds no third set.
        send { id, at -> WatchCommand.LogSet(second.setId, id, at) }
        val logged = state().exercises.single().sets
        assertEquals(2, logged.size)
        assertTrue(logged.all { it.completed })

        send { id, at -> WatchCommand.FinishWorkout(id, at) }
        assertFalse(state().isActive)
        runBlocking { assertNull(container.restTimer.stored(checkNotNull(started.workoutId))) }

        // "Last time" comes from the watch's own history.
        send { id, at -> WatchCommand.StartRoutine(routineId, id, at) }
        val next = state().exercises.single()
        assertEquals(listOf(WatchLastSet(80.0, 8), WatchLastSet(80.0, 8)), next.lastTime)
        assertEquals(listOf(80.0, 80.0), next.sets.map { it.weightKg })
    }

    @Test
    fun aSetWithoutRepsIsNotLogged() {
        send { id, at -> WatchCommand.StartWorkout(id, at) }
        val workoutId = checkNotNull(state().workoutId)
        runBlocking { container.commands.execute(Command.AddExercise(workoutId, ExerciseRef.ByKey("bench_press"))) }
        val set = state().exercises.single().sets.single()
        assertEquals(0, set.reps)

        send { id, at -> WatchCommand.LogSet(set.setId, id, at) }
        assertFalse(state().exercises.single().sets.single().completed)
        assertNull(state().rest)
    }
}
