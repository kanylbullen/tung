package io.github.kanylbullen.tung.wear

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import io.github.kanylbullen.tung.core.sync.SyncFailure
import io.github.kanylbullen.tung.core.sync.SyncResult
import io.github.kanylbullen.tung.core.wear.WatchProtocol
import io.github.kanylbullen.tung.core.wear.WatchWorkoutState
import io.github.kanylbullen.tung.ongoing.OngoingWorkout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * What the watch does when the phone's state DataItem changes, whether or not
 * the watch app is running (issue #247). Wear OS never bridges a phone's
 * ongoing notification, so the watch posts its own Ongoing Activity:
 *
 * - **Companion mode** (no token): straight from the phone's state, through
 *   [show]: a workout shows, none cancels.
 * - **Standalone mode** (a token): the phone's state is never shown; the
 *   watch shows only what its own log has. When the phone's workout is not
 *   the watch's (one was started or ended on the phone and has not synced
 *   here yet), [syncSoon] asks for a sync, which posts the watch's own
 *   Ongoing Activity once the workout has arrived ([PhoneWorkoutSyncWorker]).
 *   A change within the same workout (a set logged on the phone) asks for
 *   nothing: the chip is already there, and the app syncs when opened.
 */
class PhoneStateHandler(
    private val hasToken: suspend () -> Boolean,
    private val localWorkoutId: suspend () -> String?,
    private val show: (OngoingWorkout?) -> Unit,
    private val syncSoon: (phoneWorkoutId: String?) -> Unit,
) {
    /** [state] is the phone's latest, null when its DataItem is gone. */
    suspend fun handle(state: WatchWorkoutState?) {
        when (watchMode(hasToken())) {
            WatchMode.COMPANION -> show(watchOngoing(state))
            WatchMode.STANDALONE -> {
                val phoneWorkoutId = state?.workoutId
                if (phoneWorkoutId != localWorkoutId()) syncSoon(phoneWorkoutId)
            }
        }
    }

    companion object {
        fun of(app: TungWearApp) = PhoneStateHandler(
            hasToken = { app.container.syncAccount.isSignedIn() },
            localWorkoutId = { app.container.workoutRepository.observeActiveWorkout().first()?.id },
            show = app.ongoing::showNow,
            syncSoon = { PhoneWorkoutSyncWorker.enqueue(app, it) },
        )
    }
}

/**
 * The system starts this when the phone writes its state DataItem
 * ([WatchProtocol.STATE_PATH]), also when the watch app is not running
 * (declared in the manifest with that path). Starting the process runs
 * [TungWearApp.onCreate], which starts the Ongoing Activity's notifier; the
 * post itself is done here, synchronously, since a process with no running
 * component can be frozen before a collector gets to it.
 */
class PhoneStateListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        // The buffer is released when this returns; the states are decoded before that.
        val latest = events.mapNotNull { event ->
            if (event.dataItem.uri.path != WatchProtocol.STATE_PATH) return@mapNotNull null
            when (event.type) {
                DataEvent.TYPE_CHANGED -> decodePhoneState(event.dataItem)?.let { Seen(it) }
                DataEvent.TYPE_DELETED -> Seen(null)
                else -> null
            }
        }.lastOrNull() ?: return
        val app = application as? TungWearApp ?: return
        // Called on a background thread; bounded, so a stuck read never holds the next event back long.
        runBlocking {
            withTimeoutOrNull(HANDLE_TIMEOUT_MILLIS) {
                try {
                    PhoneStateHandler.of(app).handle(latest.state)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "Could not follow the phone's state", e)
                }
            } ?: Log.w(TAG, "Following the phone's state timed out")
        }
    }

    private class Seen(val state: WatchWorkoutState?)

    private companion object {
        const val TAG = "PhoneStateListener"
        const val HANDLE_TIMEOUT_MILLIS = 5_000L
    }
}

/** What [PhoneWorkoutSyncWorker] does after a sync: try again, or stop. */
enum class PhoneSyncNext { RETRY, DONE }

/**
 * After sync attempt [attempt] (0-based) with [result]: try again while the
 * watch's workout is still not the phone's ([phoneWorkoutId] against
 * [localWorkoutId]; the phone pushes a few seconds after a change, #199) or
 * the network failed, at most [PHONE_SYNC_ATTEMPTS] attempts in all. A
 * refusal or being signed out never retries.
 */
fun phoneSyncNext(result: SyncResult, phoneWorkoutId: String?, localWorkoutId: String?, attempt: Int): PhoneSyncNext {
    val again = when (result) {
        is SyncResult.Success -> phoneWorkoutId != localWorkoutId
        SyncResult.NotSignedIn -> false
        is SyncResult.Failed -> result.failure == SyncFailure.NETWORK || result.failure == SyncFailure.SERVER
    }
    return if (again && attempt + 1 < PHONE_SYNC_ATTEMPTS) PhoneSyncNext.RETRY else PhoneSyncNext.DONE
}

/** Syncs per phone change at most: about 5, 15, 25 and 35 s after it. */
const val PHONE_SYNC_ATTEMPTS = 4

/**
 * A standalone watch's sync after the phone's workout changed
 * ([PhoneStateHandler]): one [io.github.kanylbullen.tung.sync.SyncClient.syncOnce],
 * then the Ongoing Activity posted from the watch's own database at once
 * ([WatchOngoingActivity.refresh]), then again while the phone's workout has
 * not arrived ([phoneSyncNext]). Waits [FIRST_DELAY_SECONDS] first, since the
 * phone pushes a moment after its change; a newer change replaces it.
 */
class PhoneWorkoutSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? TungWearApp ?: return Result.success()
        val result = app.container.syncClient.syncOnce()
        app.ongoing.refresh()
        val local = app.container.workoutRepository.observeActiveWorkout().first()?.id
        return when (phoneSyncNext(result, inputData.getString(KEY_PHONE_WORKOUT_ID), local, runAttemptCount)) {
            PhoneSyncNext.RETRY -> Result.retry()
            PhoneSyncNext.DONE -> Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "tung-sync-phone-workout"
        const val KEY_PHONE_WORKOUT_ID = "phone_workout_id"
        const val FIRST_DELAY_SECONDS = 5L
        const val RETRY_SECONDS = 10L

        fun enqueue(context: Context, phoneWorkoutId: String?) {
            val request = OneTimeWorkRequestBuilder<PhoneWorkoutSyncWorker>()
                .setInitialDelay(FIRST_DELAY_SECONDS, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_SECONDS, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_PHONE_WORKOUT_ID to phoneWorkoutId))
                .build()
            // The newest change wins: it carries the phone's workout as it is now.
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
