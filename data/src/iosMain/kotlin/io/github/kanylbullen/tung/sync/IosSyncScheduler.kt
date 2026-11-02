package io.github.kanylbullen.tung.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.AtomicReference

/**
 * Sync on the iPhone while the app runs: one [syncOnce] at a time on
 * [scope], at once for [enqueueNow] and every [PERIOD_MILLIS] while
 * scheduled. iOS gives no WorkManager; background refresh
 * (BGTaskScheduler) and push come later, so for now a sync happens in front.
 *
 * [schedulePeriodic] and [cancel] are called from different threads
 * (sign-in, sign-out, the app coming to the front), so the periodic job is
 * swapped atomically: two schedules never start two loops, and a cancel
 * never leaves a loop running after sign-out.
 */
class IosSyncScheduler(
    private val scope: CoroutineScope,
    private val syncOnce: suspend () -> Unit,
) : SyncScheduler {
    private val running = Mutex()
    private val periodic = AtomicReference<Job?>(null)

    override fun schedulePeriodic() {
        while (true) {
            val current = periodic.value
            if (current?.isActive == true) return
            val next = scope.launch(start = CoroutineStart.LAZY) {
                while (isActive) {
                    delay(PERIOD_MILLIS)
                    running.withLock { runCatching { syncOnce() } }
                }
            }
            if (periodic.compareAndSet(current, next)) {
                next.start()
                return
            }
            // Another thread scheduled or cancelled in between: drop ours and look again.
            next.cancel()
        }
    }

    override fun enqueueNow() {
        scope.launch { running.withLock { runCatching { syncOnce() } } }
    }

    override fun cancel() {
        periodic.getAndSet(null)?.cancel()
    }

    private companion object {
        /** The phone's periodic work runs every 15 minutes. */
        const val PERIOD_MILLIS = 15 * 60 * 1000L
    }
}
