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
 * The wait before a device's next sync while its app is on screen. A
 * standalone watch gets no push (and a phone may have none), so this is how
 * it sees what the other one logs. After a
 * failure the wait doubles from the previous one, up to
 * [FOREGROUND_MAX_BACKOFF_MILLIS], so a device without a network does not
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
 * (issue #199). With [syncWhenIdle] false it syncs only while a workout is in
 * progress. A sync the loop cannot handle (the database) is passed to
 * [onError], never thrown.
 */
suspend fun runForegroundSync(
    account: SyncAccount,
    workouts: WorkoutRepository,
    syncWhenIdle: Boolean,
    onError: (Exception) -> Unit = {},
): Nothing = foregroundSyncLoop(
    isSignedIn = account::isSignedIn,
    workoutInProgress = { runCatching { workouts.observeActiveWorkout().first() != null }.getOrDefault(true) },
    sync = account::syncNow,
    syncWhenIdle = syncWhenIdle,
    onError = onError,
)

/** [runForegroundSync] over its three questions, so a test can drive it; [nextDelay] and [firstDelay] let it run in milliseconds. */
internal suspend fun foregroundSyncLoop(
    isSignedIn: suspend () -> Boolean,
    workoutInProgress: suspend () -> Boolean,
    sync: suspend () -> SyncResult,
    syncWhenIdle: Boolean,
    onError: (Exception) -> Unit = {},
    firstDelay: Long = FOREGROUND_SYNC_MILLIS,
    nextDelay: (SyncResult?, Boolean, Long) -> Long = ::nextForegroundSyncDelay,
): Nothing {
    var wait = firstDelay
    while (true) {
        delay(wait)
        val inProgress = workoutInProgress()
        val result = if ((inProgress || syncWhenIdle) && isSignedIn()) {
            try {
                sync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                null
            }
        } else {
            null
        }
        wait = nextDelay(result, inProgress, wait)
    }
}
