package io.github.kanylbullen.tung.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.kanylbullen.tung.TungApp
import io.github.kanylbullen.tung.core.sync.SyncFailure
import java.util.concurrent.TimeUnit

/** When background syncs run. [WorkManagerSyncScheduler] in the app. */
interface SyncScheduler {
    /** Every 15 minutes while signed in, with a network. Kept if already scheduled. */
    fun schedulePeriodic()

    /** One sync as soon as there is a network, after any that is running. */
    fun enqueueNow()

    /** Both, on sign out. */
    fun cancel()
}

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
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        // Appended, not replacing: a sync in flight finishes, and this one picks up what changed meanwhile.
        workManager.enqueueUniqueWork(NOW_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
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
    override suspend fun doWork(): Result {
        val client = (applicationContext as TungApp).container.syncClient
        return when (val result = client.syncOnce()) {
            is SyncResult.Success, SyncResult.NotSignedIn -> Result.success()
            is SyncResult.Failed -> when (result.failure) {
                SyncFailure.NETWORK, SyncFailure.SERVER -> Result.retry()
                SyncFailure.SIGNED_OUT, SyncFailure.REJECTED -> Result.failure()
            }
        }
    }
}
