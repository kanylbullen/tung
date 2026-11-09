package io.github.kanylbullen.tung.sync

import io.github.kanylbullen.tung.core.sync.SyncFailure
import io.github.kanylbullen.tung.core.sync.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Test

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
