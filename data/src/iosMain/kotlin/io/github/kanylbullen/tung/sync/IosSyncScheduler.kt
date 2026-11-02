package io.github.kanylbullen.tung.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sync on the iPhone while the app runs: one [syncOnce] at a time on
 * [scope], at once for [enqueueNow] and every [PERIOD_MILLIS] while
 * scheduled. iOS gives no WorkManager; background refresh
 * (BGTaskScheduler) and push come later, so for now a sync happens in front.
 */
class IosSyncScheduler(
    private val scope: CoroutineScope,
    private val syncOnce: suspend () -> Unit,
) : SyncScheduler {
    private val running = Mutex()
    private var periodic: Job? = null

    override fun schedulePeriodic() {
        if (periodic?.isActive == true) return
        periodic = scope.launch {
            while (isActive) {
                delay(PERIOD_MILLIS)
                running.withLock { runCatching { syncOnce() } }
            }
        }
    }

    override fun enqueueNow() {
        scope.launch { running.withLock { runCatching { syncOnce() } } }
    }

    override fun cancel() {
        periodic?.cancel()
        periodic = null
    }

    private companion object {
        /** The phone's periodic work runs every 15 minutes. */
        const val PERIOD_MILLIS = 15 * 60 * 1000L
    }
}
