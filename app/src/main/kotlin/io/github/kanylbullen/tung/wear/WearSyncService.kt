package io.github.kanylbullen.tung.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import io.github.kanylbullen.tung.TungApp
import io.github.kanylbullen.tung.core.wear.WatchProtocol
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives the watch's commands. The system starts it (and the app's process,
 * if needed) when a message arrives at [WatchProtocol.COMMAND_PATH], whether
 * or not the app is open.
 *
 * The callback runs on a background thread and the service may be stopped as
 * soon as it returns, so the command is applied and the state published before
 * returning, under a timeout.
 */
class WearSyncService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchProtocol.COMMAND_PATH) return
        val container = (application as TungApp).container
        val publisher = container.wearPublisher
        publisher.start()
        val text = event.data.toString(Charsets.UTF_8)
        val done = runBlocking {
            withTimeoutOrNull(TIMEOUT_MILLIS) {
                container.wearCommands.handle(text)
                publisher.publishNow()
            }
        }
        if (done == null) Log.w(TAG, "A watch command timed out")
    }

    private companion object {
        const val TAG = "WearSyncService"
        const val TIMEOUT_MILLIS = 10_000L
    }
}
