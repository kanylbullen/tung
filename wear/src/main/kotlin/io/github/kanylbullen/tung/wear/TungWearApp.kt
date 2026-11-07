package io.github.kanylbullen.tung.wear

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.Configuration
import io.github.kanylbullen.tung.core.StartedBy
import io.github.kanylbullen.tung.core.sync.SyncResult
import io.github.kanylbullen.tung.data.TungContainer
import io.github.kanylbullen.tung.data.TungHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The watch app. It builds the same [TungContainer] as the phone: its own
 * Room database, repositories, CommandHandler and sync client, used in
 * standalone mode ([WatchMode]). In companion mode the container sits idle
 * apart from the settings, and the phone's database is the one that counts.
 */
class TungWearApp : Application(), Configuration.Provider, TungHost {
    override val container: TungContainer by lazy { TungContainer(this, startedBy = StartedBy.WATCH) }

    /** Standalone mode's backend, one per process: the screens and the Ongoing Activity share its current-set selection. */
    val localBackend: LocalWatchBackend by lazy { LocalWatchBackend(container, this) }

    /** The Ongoing Activity of the workout in progress, in both modes ([WatchOngoingActivity]). */
    private val ongoing: WatchOngoingActivity by lazy {
        WatchOngoingActivity(this, container, local = { localBackend }, phone = { PhoneLink(this) })
    }

    private var startedActivities = 0

    override val isInForeground: Boolean get() = startedActivities > 0

    override val notificationIcon: Int get() = R.drawable.ic_dumbbell

    /** The watch has one screen stack; opening it shows the workout in progress. */
    override fun openWorkoutIntent(workoutId: String): Intent = Intent(this, MainActivity::class.java)

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities++ }
            override fun onActivityStopped(activity: Activity) { startedActivities-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        ongoing.start()
        // The phone's triggers: periodic work while signed in, and a sync whenever the app comes to the
        // foreground. A watch gets no push, so while it is on screen it also syncs now and then, to see
        // what the phone logs meanwhile (issue #199; nextForegroundSyncDelay): directly, not through
        // WorkManager, so a watch offline on screen does not queue a sync every round. Signed out
        // (companion mode) each is a no-op.
        container.syncAccount.restoreSchedule()
        val process = ProcessLifecycleOwner.get()
        process.lifecycleScope.launch {
            process.repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.syncAccount.requestSync()
                var wait = FOREGROUND_SYNC_MILLIS
                var result: SyncResult? = null
                while (true) {
                    val inProgress = runCatching { container.workoutRepository.observeActiveWorkout().first() != null }.getOrDefault(true)
                    wait = nextForegroundSyncDelay(result, inProgress, wait)
                    delay(wait)
                    result = if (container.syncAccount.isSignedIn()) foregroundSync() else null
                }
            }
        }
    }

    /** One sync from the loop above. An error the sync does not handle itself (the database) is logged, never thrown at the app. */
    private suspend fun foregroundSync(): SyncResult? = try {
        container.syncAccount.syncNow()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Foreground sync failed", e)
        null
    }

    /** WorkManager is initialised on demand (the default initializer is removed in the manifest). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}

private const val TAG = "TungWearApp"
