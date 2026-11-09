package io.github.kanylbullen.tung.sync

import io.github.kanylbullen.tung.core.sync.SyncFailure
import io.github.kanylbullen.tung.core.sync.SyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** How often a standalone watch on screen syncs (issue #199): often in a workout, rarely outside one, backing off without a network. */
class ForegroundSyncTest {
    private val unreachable = SyncResult.Failed(SyncFailure.NETWORK)

    @Test
    fun aWorkoutInProgressSyncsEveryThirtySecondsAndOtherwiseRarely() {
        assertEquals(FOREGROUND_SYNC_MILLIS, nextForegroundSyncDelay(null, workoutInProgress = true, previousMillis = FOREGROUND_SYNC_MILLIS))
        assertEquals(FOREGROUND_IDLE_SYNC_MILLIS, nextForegroundSyncDelay(null, workoutInProgress = false, previousMillis = FOREGROUND_SYNC_MILLIS))
    }

    @Test
    fun withoutANetworkTheWaitDoublesUpToFiveMinutes() {
        var wait = FOREGROUND_SYNC_MILLIS
        val waits = List(6) { nextForegroundSyncDelay(unreachable, true, wait).also { wait = it } }
        assertEquals(listOf(60_000L, 120_000L, 240_000L, 300_000L, 300_000L, 300_000L), waits)
        assertEquals(240_000L, nextForegroundSyncDelay(SyncResult.Failed(SyncFailure.SERVER), true, 120_000L))
    }

    @Test
    fun anythingElseStartsOverAtTheNormalInterval() {
        assertEquals(FOREGROUND_SYNC_MILLIS, nextForegroundSyncDelay(SyncResult.Failed(SyncFailure.REJECTED), true, 300_000L))
        assertEquals(FOREGROUND_SYNC_MILLIS, nextForegroundSyncDelay(SyncResult.NotSignedIn, true, 300_000L))
        assertEquals(FOREGROUND_SYNC_MILLIS, nextForegroundSyncDelay(null, true, 300_000L))
    }
}

/** The loop itself, in milliseconds: when it syncs, when it does not, and what it passes on. */
class ForegroundSyncLoopTest {
    /** Runs the loop for a few rounds and returns how many times it called `sync`. */
    private fun syncsWith(
        signedIn: Boolean = true,
        inProgress: Boolean = true,
        syncWhenIdle: Boolean,
        sync: () -> SyncResult = { SyncResult.NotSignedIn },
        onError: (Exception) -> Unit = {},
    ): Int {
        val syncs = AtomicInteger()
        runBlocking {
            val job = launch(Dispatchers.Default) {
                foregroundSyncLoop(
                    isSignedIn = { signedIn },
                    workoutInProgress = { inProgress },
                    sync = {
                        syncs.incrementAndGet()
                        sync()
                    },
                    syncWhenIdle = syncWhenIdle,
                    onError = onError,
                    firstDelay = 5L,
                    nextDelay = { _, _, _ -> 5L },
                )
            }
            delay(150L)
            job.cancelAndJoin()
        }
        return syncs.get()
    }

    @Test
    fun syncsEveryRoundInAWorkout() {
        assertTrue(syncsWith(syncWhenIdle = false) >= 2)
    }

    @Test
    fun idleSyncsOnlyWhenAskedTo() {
        assertEquals(0, syncsWith(inProgress = false, syncWhenIdle = false))
        assertTrue(syncsWith(inProgress = false, syncWhenIdle = true) >= 2)
    }

    @Test
    fun signedOutNeverSyncs() {
        assertEquals(0, syncsWith(signedIn = false, syncWhenIdle = true))
    }

    @Test
    fun anErrorIsPassedOnAndTheLoopGoesOn() {
        val errors = AtomicInteger()
        val syncs = syncsWith(syncWhenIdle = true, sync = { error("database") }, onError = { errors.incrementAndGet() })
        assertTrue(syncs >= 2)
        assertEquals(syncs, errors.get())
    }
}
