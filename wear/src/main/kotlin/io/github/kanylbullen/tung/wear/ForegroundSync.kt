package io.github.kanylbullen.tung.wear

import io.github.kanylbullen.tung.core.sync.SyncFailure
import io.github.kanylbullen.tung.core.sync.SyncResult

/** How often a standalone watch syncs while the app is on screen and a workout is in progress (issue #199). */
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
