package io.github.kanylbullen.tung.flows

import io.github.kanylbullen.tung.ui.resources.*
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.android.gms.wearable.MessageEvent
import io.github.kanylbullen.tung.core.sync.SyncChanges
import io.github.kanylbullen.tung.core.sync.SyncResult
import io.github.kanylbullen.tung.core.sync.SyncTable
import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.github.kanylbullen.tung.core.sync.entries
import io.github.kanylbullen.tung.core.sync.tombstoneEntry
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import io.github.kanylbullen.tung.core.wear.WatchProtocol
import io.github.kanylbullen.tung.sync.AuthResult
import io.github.kanylbullen.tung.sync.FakeSyncServer
import io.github.kanylbullen.tung.wear.WearSyncService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Robolectric

/**
 * Issue #249 as the owner met it: a workout open on the phone is finished on
 * a standalone watch. The watch's "sync now" reaches the phone, which syncs
 * at once, and the open workout screen leaves for the summary instead of
 * showing a workout that is over as if it ran.
 */
class FinishedElsewhereFlowTest : FlowTest() {

    private val fake = FakeSyncServer()

    @Before
    fun startServer() {
        fake.start()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @After
    fun stopServer() = fake.stop()

    private fun nudgeFromWatch() {
        val event = object : MessageEvent {
            override fun getRequestId(): Int = 1
            override fun getPath(): String = WatchProtocol.SYNC_PATH
            override fun getData(): ByteArray = ByteArray(0)
            override fun getSourceNodeId(): String = "watch"
        }
        Robolectric.buildService(WearSyncService::class.java).create().get().onMessageReceived(event)
    }

    private fun signInAndStart(): String {
        val signedIn = runBlocking {
            app.container.syncAccount.signIn(fake.baseUrl, "lifter@example.org", "correct horse battery", create = false)
        }
        assertTrue("$signedIn", signedIn is AuthResult.Success)
        startWorkoutFromHome()
        val workoutId = runBlocking { checkNotNull(app.container.workoutRepository.observeActiveWorkout().first()).id }
        assertTrue(runBlocking { app.container.syncAccount.syncNow() } is SyncResult.Success)
        return workoutId
    }

    @Test
    fun aWorkoutFinishedOnTheWatchWithNoLoggedSetLeavesForHome() {
        val workoutId = signInAndStart()

        // Finishing with no logged set deletes the workout: the server gets a tombstone.
        val stored = fake.stored(SyncTable.WORKOUTS, workoutId)?.row as SyncWorkout
        fake.put(tombstoneEntry(SyncTable.WORKOUTS, workoutId, stored.updatedAt + 60_000))

        nudgeFromWatch()

        waitFor(hasText(str(Res.string.home_start_workout)) and isEnabled())
        screenshot("home")
    }

    @Test
    fun aWorkoutFinishedOnTheWatchLeavesTheWorkoutScreen() {
        val workoutId = signInAndStart()
        screenshot("1-workout-open")

        // The watch finishes the workout, and its sync carries the finish up.
        val stored = fake.stored(SyncTable.WORKOUTS, workoutId)?.row as SyncWorkout
        val at = stored.updatedAt + 60_000
        fake.put(SyncChanges(workouts = listOf(stored.copy(endedAt = at, updatedAt = at))).entries().single())

        nudgeFromWatch()

        waitForText(str(Res.string.summary_title))
        screenshot("2-summary")
        assertNull(runBlocking { app.container.workoutRepository.observeActiveWorkout().first() })
    }
}
