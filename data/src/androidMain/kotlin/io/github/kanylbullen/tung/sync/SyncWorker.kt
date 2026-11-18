package io.github.kanylbullen.tung.sync

import io.github.kanylbullen.tung.core.sync.SyncResult
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.OutOfQuotaPolicy
import io.github.kanylbullen.tung.data.R
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import kotlinx.coroutines.flow.first
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.kanylbullen.tung.data.TungHost
import io.github.kanylbullen.tung.core.sync.SyncFailure
import java.util.concurrent.TimeUnit

class WorkManagerSyncScheduler(private val context: Context) : SyncScheduler {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun enqueueNow() {
        // Expedited (issue #249): a sync asked for by a push, a watch, a finish or a change is wanted
        // now, and a plain job is held back by Doze or a low standby bucket for minutes. Out of quota
        // it runs as an ordinary job, as before.
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        // Appended, not replacing: a sync in flight finishes, and this one picks up what changed meanwhile.
        workManager.enqueueUniqueWork(NOW_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override suspend fun enqueueSoon() {
        // Enqueued (waiting for a network) or blocked behind the running one: it has not read the
        // dirty rows yet, so it takes this change along. Only a running sync, or none, needs another.
        val waiting = workManager.getWorkInfosForUniqueWorkFlow(NOW_WORK).first()
            .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        if (!waiting) enqueueNow()
    }

    override fun cancel() {
        workManager.cancelUniqueWork(PERIODIC_WORK)
        workManager.cancelUniqueWork(NOW_WORK)
    }

    companion object {
        const val PERIODIC_WORK = "tung-sync-periodic"
        const val NOW_WORK = "tung-sync-now"
        const val BACKOFF_SECONDS = 30L
    }
}

/**
 * One [SyncClient.syncOnce] in the background. Network trouble and 5xx are
 * retried with WorkManager's exponential backoff; a 401 or another refusal
 * is not, because trying again changes nothing.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    /**
     * Below Android 12 WorkManager runs expedited work as a foreground service, which needs a
     * notification: a silent, minimal one for the moment the sync takes. From 12 on it is a
     * JobScheduler expedited job and this is not called.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val context = applicationContext
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.sync_channel_name), NotificationManager.IMPORTANCE_MIN)
                    .apply { description = context.getString(R.string.sync_channel_description) },
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon((context as TungHost).notificationIcon)
            .setContentTitle(context.getString(R.string.sync_notification_title))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    override suspend fun doWork(): Result {
        val client = (applicationContext as TungHost).container.syncClient
        return when (val result = client.syncOnce()) {
            is SyncResult.Success, SyncResult.NotSignedIn -> Result.success()
            is SyncResult.Failed -> when (result.failure) {
                SyncFailure.NETWORK, SyncFailure.SERVER -> Result.retry()
                SyncFailure.SIGNED_OUT, SyncFailure.REJECTED, SyncFailure.EMAIL_NOT_VERIFIED -> Result.failure()
            }
        }
    }

    private companion object {
        const val CHANNEL_ID = "sync"
        /** "Rest over" is 1 and the ongoing workout 2. */
        const val NOTIFICATION_ID = 3
    }
}
