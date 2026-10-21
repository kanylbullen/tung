package io.github.kanylbullen.tung.wear

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.wear.WatchCommand
import io.github.kanylbullen.tung.core.wear.WatchProtocol
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.SetEntry
import io.github.kanylbullen.tung.data.TungDatabase
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.timer.RestTimerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The phone's side of the watch protocol: a command as JSON in, the same rows
 * the phone's own buttons would write out. Real Room (in memory), the real
 * rest-timer controller over a DataStore in a temporary folder.
 */
@RunWith(RobolectricTestRunner::class)
class WearCommandHandlerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: TungDatabase
    private lateinit var workouts: WorkoutRepository
    private lateinit var routines: RoutineRepository
    private lateinit var restTimer: RestTimerController
    private lateinit var publisher: WearStatePublisher
    private lateinit var handler: WearCommandHandler
    private val written = mutableListOf<String>()
    private var commandCount = 0

    @Before
    fun setUp() {
        db = TungDatabase.buildInMemory(context)
        workouts = WorkoutRepository(db)
        routines = RoutineRepository(db)
        val settings = PreferenceDataStoreFactory.create(scope = dataStoreScope) {
            File(tmp.root, "settings.preferences_pb")
        }
        restTimer = RestTimerController(context, settings)
        publisher = WearStatePublisher(context, workouts, routines, restTimer, settings.data, { written += it })
        handler = WearCommandHandler(workouts, routines, restTimer, publisher.selection)
    }

    @After
    fun tearDown() {
        db.close()
        dataStoreScope.cancel()
    }

    private fun json(make: (commandId: String, sentAt: Long) -> WatchCommand): String =
        WatchProtocol.encodeCommand(make("cmd-${++commandCount}", 1_000L))

    /** A workout with bench press, its first set at 80 × 8. */
    private suspend fun benchWorkout(): Pair<String, SetEntry> {
        val workoutId = workouts.startOrResumeWorkout()
        workouts.addExercise(workoutId, catalogExerciseId("bench_press"), sets = 1)
        val set = sets(workoutId).single()
        workouts.updateWeight(set.id, 80.0)
        workouts.updateReps(set.id, 8)
        return workoutId to set
    }

    private suspend fun sets(workoutId: String): List<SetEntry> =
        workouts.observeExercises(workoutId).first().single().sortedSets

    @Test
    fun logSetTicksTheSetAddsTheNextAndStartsTheRest() = runBlocking {
        val (workoutId, first) = benchWorkout()

        assertTrue(handler.handle(json { id, at -> WatchCommand.LogSet(first.id, id, at) }))

        val after = sets(workoutId)
        assertEquals(2, after.size)
        assertTrue(after[0].completed)
        assertEquals(first.id, after[0].id)
        assertFalse(after[1].completed)
        assertEquals(80.0, after[1].weightKg, 0.0)
        assertEquals(8, after[1].reps)

        val rest = restTimer.stored(workoutId)
        assertNotNull(rest)
        assertEquals(90_000L, rest?.durationMillis)

        val state = publisher.snapshot()
        assertEquals(workoutId, state.workoutId)
        assertEquals(after[1].id, state.currentSetId)
        assertNotNull(state.rest)
        assertEquals(listOf(true, false), state.exercises.single().sets.map { it.completed })
        assertEquals("Bench press", state.exercises.single().name)
    }

    @Test
    fun aRepeatedCommandIdIsAppliedOnce() = runBlocking {
        val (workoutId, first) = benchWorkout()
        val text = json { id, at -> WatchCommand.LogSet(first.id, id, at) }
        assertTrue(handler.handle(text))
        assertFalse(handler.handle(text))
        assertEquals(2, sets(workoutId).size)
    }

    @Test
    fun aSetWithoutRepsIsNotLogged() = runBlocking {
        val (workoutId, first) = benchWorkout()
        workouts.updateReps(first.id, 0)
        assertFalse(handler.handle(json { id, at -> WatchCommand.LogSet(first.id, id, at) }))
        assertFalse(sets(workoutId).single().completed)
        assertNull(restTimer.stored(workoutId))
    }

    @Test
    fun weightAndRepsAreWrittenAndCheckedForRange() = runBlocking {
        val (workoutId, first) = benchWorkout()
        assertTrue(handler.handle(json { id, at -> WatchCommand.SetWeight(first.id, 82.5, id, at) }))
        assertTrue(handler.handle(json { id, at -> WatchCommand.SetReps(first.id, 6, id, at) }))
        assertFalse(handler.handle(json { id, at -> WatchCommand.SetWeight(first.id, 1_500.0, id, at) }))
        assertFalse(handler.handle(json { id, at -> WatchCommand.SetReps("not-a-set", 5, id, at) }))
        val set = sets(workoutId).single()
        assertEquals(82.5, set.weightKg, 0.0)
        assertEquals(6, set.reps)
    }

    @Test
    fun selectSetMovesTheCurrentSet() = runBlocking {
        val (workoutId, first) = benchWorkout()
        val second = workouts.addSet(sets(workoutId).single().workoutExerciseId)
        assertNotNull(second)
        assertEquals(first.id, publisher.snapshot().currentSetId)
        assertTrue(handler.handle(json { id, at -> WatchCommand.SelectSet(second.orEmpty(), id, at) }))
        assertEquals(second, publisher.snapshot().currentSetId)
    }

    @Test
    fun restCanBeExtendedAndSkipped() = runBlocking {
        val (workoutId, first) = benchWorkout()
        handler.handle(json { id, at -> WatchCommand.LogSet(first.id, id, at) })
        val started = restTimer.stored(workoutId)
        assertNotNull(started)

        assertTrue(handler.handle(json { id, at -> WatchCommand.AddRest30(id, at) }))
        assertEquals(120_000L, restTimer.stored(workoutId)?.durationMillis)

        assertTrue(handler.handle(json { id, at -> WatchCommand.SkipRest(id, at) }))
        assertNull(restTimer.stored(workoutId))
        assertNull(publisher.snapshot().rest)
    }

    @Test
    fun finishThenStartARoutineFromTheWatch() = runBlocking {
        val (workoutId, first) = benchWorkout()
        handler.handle(json { id, at -> WatchCommand.LogSet(first.id, id, at) })
        assertTrue(handler.handle(json { id, at -> WatchCommand.FinishWorkout(id, at) }))
        assertNull(workouts.observeActiveWorkout().first())
        assertNull(restTimer.stored(workoutId))

        val routineId = routines.saveFromWorkout(workoutId, "Push")
        assertNotNull(routineId)
        val idle = publisher.snapshot()
        assertNull(idle.workoutId)
        assertEquals(listOf("Push"), idle.routines.map { it.name })

        assertTrue(handler.handle(json { id, at -> WatchCommand.StartRoutine(routineId.orEmpty(), id, at) }))
        val active = workouts.observeActiveWorkout().first()
        assertEquals(routineId, active?.routineId)
        val state = publisher.snapshot()
        assertEquals(active?.id, state.workoutId)
        // Last session's set shows as "Last:" on the watch.
        assertEquals(80.0, state.exercises.single().lastTime.single().weightKg, 0.0)
    }

    @Test
    fun garbageIsIgnored() = runBlocking {
        assertFalse(handler.handle("not json"))
        assertFalse(handler.handle("""{"type":"launch_rockets","commandId":"x","sentAt":1}"""))
    }

    @Test
    fun publishNowWritesOnlyChanges() = runBlocking {
        benchWorkout()
        publisher.publishNow()
        publisher.publishNow()
        assertEquals(1, written.size)
        assertEquals(publisher.snapshot().copy(updatedAt = 0L), WatchProtocol.decodeState(written.single()).copy(updatedAt = 0L))
    }

    @Test
    fun theTestApplicationDoesNotStartThePublisher() {
        // No Play services under Robolectric: TungApp must not touch the Data Layer.
        assertFalse(isWearableApiAvailable(context))
    }
}
