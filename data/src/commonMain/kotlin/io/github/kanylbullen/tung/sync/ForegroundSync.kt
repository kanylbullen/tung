package io.github.kanylbullen.tung.sync

import io.github.kanylbullen.tung.core.sync.SyncFailure
import io.github.kanylbullen.tung.core.sync.SyncResult
import io.github.kanylbullen.tung.data.WorkoutRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** How often a device on screen syncs while the app is on screen and a workout is in progress (issue #199). */
const val FOREGROUND_SYNC_MILLIS = 30_000L

/** The same with no workout in progress: a workout the phone starts still shows up, but rarely costs a request. */
const val FOREGROUND_IDLE_SYNC_MILLIS = 120_000L

/** The longest wait after syncs that failed for want of a network or server. */
const val FOREGROUND_MAX_BACKOFF_MILLIS = 300_000L

/**
 * The wait before a standalone watch's next sync while it is on screen. A
 * watch gets no push, so this is how it sees what the phone logs. After a
 * failure the wait doubles from the previous one, up to
 * [FOREGROUND_MAX_BACKOFF_MILLIS], so a watch without a network does not
 * keep its radio busy; anything else starts over at the normal interval.
 */
fun nextForegroundSyncDelay(result: SyncResult?, workoutInProgress: Boolean, previousMillis: Long): Long {
    val normal = if (workoutInProgress) FOREGROUND_SYNC_MILLIS else FOREGROUND_IDLE_SYNC_MILLIS
    val unreachable = result is SyncResult.Failed &&
        (result.failure == SyncFailure.NETWORK || result.failure == SyncFailure.SERVER)
    return if (unreachable) (previousMillis * 2).coerceIn(normal, FOREGROUND_MAX_BACKOFF_MILLIS) else normal
}

/**
 * Syncs now and then for as long as the caller's scope lives (run it while the
 * app is on screen): a device with no push still sees what another one logs
 * (issue #199). [syncWhenIdle] false (the phone: push and the periodic work
 * cover it) syncs only while a workout is in progress. A sync the loop cannot
 * handle (the database) is passed to [onError], never thrown.
 */
suspend fun runForegroundSync(
    account: SyncAccount,
    workouts: WorkoutRepository,
    syncWhenIdle: Boolean,
    onError: (Exception) -> Unit = {},
): Nothing {
    var wait = FOREGROUND_SYNC_MILLIS
    var result: SyncResult? = null
    while (true) {
        delay(wait)
        val inProgress = runCatching { workouts.observeActiveWorkout().first() != null }.getOrDefault(true)
        result = if ((inProgress || syncWhenIdle) && account.isSignedIn()) {
            try {
                account.syncNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                null
            }
        } else {
            null
        }
        wait = nextForegroundSyncDelay(result, inProgress, wait)
    }
}
