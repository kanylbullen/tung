package io.github.kanylbullen.tung.web.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * How often the open page syncs on its own. The phone syncs every 15 min in
 * the background and is woken by push; a page gets no push, so it asks more
 * often, but only while it is shown.
 */
const val WEB_SYNC_INTERVAL_MILLIS = 5 * 60_000L

/**
 * A sync every [intervalMillis] while the page [isVisible] and [isSignedIn];
 * a hidden page or a signed-out one skips its turn (showing the page again
 * syncs at once anyway, `WebContainer`). [sync] is the container's sync,
 * which runs one at a time.
 */
class PeriodicSync(
    private val intervalMillis: Long = WEB_SYNC_INTERVAL_MILLIS,
    private val isVisible: () -> Boolean,
    private val isSignedIn: () -> Boolean,
    private val sync: suspend () -> Unit,
) {
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            delay(intervalMillis)
            if (isVisible() && isSignedIn()) sync()
        }
    }
}
