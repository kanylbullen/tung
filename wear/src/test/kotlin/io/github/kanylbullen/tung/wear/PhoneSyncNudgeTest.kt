package io.github.kanylbullen.tung.wear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.command.id
import io.github.kanylbullen.tung.core.sync.SyncResult
import io.github.kanylbullen.tung.core.sync.SyncTable
import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.github.kanylbullen.tung.data.TungContainer
import io.github.kanylbullen.tung.data.TungDatabase
import io.github.kanylbullen.tung.data.buildInMemory
import io.github.kanylbullen.tung.sync.FakeSyncServer
import io.github.kanylbullen.tung.sync.MemoryTokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * A standalone watch tells the phone to sync once its own sync has carried a
 * change up (issue #249): after a finished workout, not after a sync that
 * carried nothing, and never while signed out.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneSyncNudgeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fake = FakeSyncServer()
    private val db = TungDatabase.buildInMemory(context)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val nudges = AtomicInteger()
    private val nudger = PhoneNudger { nudges.incrementAndGet(); true }

    private fun watch(token: String?): TungContainer =
        TungContainer(context, createDatabase = { db }, createTokenStore = { MemoryTokenStore(token) })

    @Before
    fun start() = fake.start()

    @After
    fun stop() {
        scope.cancel()
        fake.stop()
        db.close()
    }

    /** The app's collector, as `TungWearApp` starts it, subscribed before anything syncs. */
    private fun follow(watch: TungContainer) = runBlocking {
        val started = AtomicInteger()
        scope.launch {
            started.set(1)
            nudgePhoneAfterPushes(watch.syncClient.pushes, nudger)
        }
        while (started.get() == 0) yield()
        // The SharedFlow has no replay: give the collector a moment to subscribe.
        Thread.sleep(100)
    }

    private fun waitForNudges(count: Int) {
        val until = System.currentTimeMillis() + 5_000
        while (nudges.get() < count && System.currentTimeMillis() < until) Thread.sleep(20)
    }

    @Test
    fun aFinishedWorkoutThatSyncedUpNudgesThePhone() = runBlocking {
        val watch = watch(fake.token)
        watch.syncSettings.signedIn(fake.baseUrl, "lifter@example.org")
        follow(watch)

        val started = watch.commands.execute(Command.StartWorkout())
        val workoutId = checkNotNull(started.id(CommandIds.WORKOUT_ID))
        val added = watch.commands.execute(Command.AddExercise(workoutId, ExerciseRef.ByKey("bench_press"), sets = 1))
        val workoutExerciseId = checkNotNull(added.id(CommandIds.WORKOUT_EXERCISE_ID))
        // AddSet names the set it adds; the unticked first set goes at finish.
        val setId = checkNotNull(watch.commands.execute(Command.AddSet(workoutExerciseId)).id(CommandIds.SET_ID))
        watch.commands.execute(Command.SetReps(setId, 8))
        watch.commands.execute(Command.LogSet(workoutId, setId))
        assertTrue(watch.commands.execute(Command.FinishWorkout(workoutId)) is CommandResult.Done)

        val result = watch.syncClient.syncOnce()
        assertTrue("$result", result is SyncResult.Success && result.pushed > 0)
        // The finish is on the server before the phone is told.
        assertNotNull((fake.stored(SyncTable.WORKOUTS, workoutId)?.row as? SyncWorkout)?.endedAt)
        waitForNudges(1)
        assertEquals(1, nudges.get())
    }

    @Test
    fun aSyncThatCarriedNothingUpDoesNotNudge() = runBlocking {
        val watch = watch(fake.token)
        watch.syncSettings.signedIn(fake.baseUrl, "lifter@example.org")
        follow(watch)

        val result = watch.syncClient.syncOnce()
        assertEquals(SyncResult.Success(pushed = 0, applied = 0), result)
        Thread.sleep(200)
        assertEquals(0, nudges.get())
    }

    @Test
    fun aSignedOutWatchNeverNudges() = runBlocking {
        val watch = watch(token = null)
        follow(watch)

        val started = watch.commands.execute(Command.StartWorkout())
        val workoutId = checkNotNull(started.id(CommandIds.WORKOUT_ID))
        watch.commands.execute(Command.FinishWorkout(workoutId))

        assertEquals(SyncResult.NotSignedIn, watch.syncClient.syncOnce())
        Thread.sleep(200)
        assertEquals(0, nudges.get())
        assertTrue(fake.syncRequests.isEmpty())
    }
}
