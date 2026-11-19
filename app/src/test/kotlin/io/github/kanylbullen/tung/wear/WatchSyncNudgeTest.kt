package io.github.kanylbullen.tung.wear

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.android.gms.wearable.MessageEvent
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.id
import io.github.kanylbullen.tung.core.sync.SyncChanges
import io.github.kanylbullen.tung.sync.AuthResult
import io.github.kanylbullen.tung.core.sync.SyncResult
import io.github.kanylbullen.tung.core.sync.SyncTable
import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.github.kanylbullen.tung.core.sync.entries
import io.github.kanylbullen.tung.core.wear.WatchProtocol
import io.github.kanylbullen.tung.flows.FlowTestApp
import io.github.kanylbullen.tung.sync.FakeSyncServer
import io.github.kanylbullen.tung.sync.WorkManagerSyncScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * A standalone watch's "sync now" on the phone (issue #249): delivered to the
 * real [WearSyncService], it syncs at once, so a workout the watch finished
 * stops being in progress here; signed out, it does nothing.
 */
@RunWith(RobolectricTestRunner::class)
class WatchSyncNudgeTest {

    private val app: FlowTestApp = ApplicationProvider.getApplicationContext()
    private val fake = FakeSyncServer()

    @Before
    fun start() {
        fake.start()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @After
    fun stop() = fake.stop()

    private fun message(path: String): MessageEvent = object : MessageEvent {
        override fun getRequestId(): Int = 1
        override fun getPath(): String = path
        override fun getData(): ByteArray = ByteArray(0)
        override fun getSourceNodeId(): String = "watch"
    }

    private fun deliver(path: String) {
        val service = Robolectric.buildService(WearSyncService::class.java).create().get()
        service.onMessageReceived(message(path))
    }

    /** A real sign-in, as Account does it: [io.github.kanylbullen.tung.sync.SyncAccount] remembers it. */
    private suspend fun signIn() {
        val result = app.container.syncAccount.signIn(fake.baseUrl, "lifter@example.org", "correct horse battery", create = false)
        assertTrue("$result", result is AuthResult.Success)
    }

    private fun nowWork() =
        WorkManager.getInstance(app).getWorkInfosForUniqueWork(WorkManagerSyncScheduler.NOW_WORK).get()

    @Test
    fun theWatchsFinishEndsTheWorkoutHereAtOnce() = runBlocking {
        signIn()
        val workouts = app.container.workoutRepository

        // A workout in progress on the phone, synced up, as both devices saw it.
        val workoutId = checkNotNull(app.container.commands.execute(Command.StartWorkout()).id(CommandIds.WORKOUT_ID))
        assertTrue(app.container.syncAccount.syncNow() is SyncResult.Success)
        val stored = fake.stored(SyncTable.WORKOUTS, workoutId)?.row as SyncWorkout
        assertNotNull(workouts.observeActiveWorkout().first())

        // The watch finishes it and its sync carries the finish up.
        val finished = stored.copy(endedAt = stored.updatedAt + 60_000, updatedAt = stored.updatedAt + 60_000)
        fake.put(SyncChanges(workouts = listOf(finished)).entries().single())
        val before = fake.syncRequests.size

        deliver(WatchProtocol.SYNC_PATH)

        // One sync, inside the message callback, not a job WorkManager might hold back.
        assertEquals(before + 1, fake.syncRequests.size)
        assertNull(workouts.observeActiveWorkout().first())
    }

    @Test
    fun signedOutTheNudgeDoesNothing() = runBlocking {
        // Never signed in on this phone.
        deliver(WatchProtocol.SYNC_PATH)

        assertTrue(fake.syncRequests.isEmpty())
        assertTrue(nowWork().isEmpty())
    }

    @Test
    fun aFailedSyncIsHandedToWorkManager() = runBlocking {
        signIn()
        // Signing in enqueued a first sync (never run: the test WorkManager's network is never there).
        val until0 = System.currentTimeMillis() + 5_000
        while (nowWork().isEmpty() && System.currentTimeMillis() < until0) Thread.sleep(20)
        val before = nowWork().size
        // Nothing listens on this port: the sync fails as unreachable.
        app.container.syncSettings.signedIn("http://127.0.0.1:9", "lifter@example.org")

        deliver(WatchProtocol.SYNC_PATH)

        // requestSync enqueues on the app's scope.
        val until = System.currentTimeMillis() + 5_000
        while (nowWork().size == before && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals(before + 1, nowWork().size)
    }
}
