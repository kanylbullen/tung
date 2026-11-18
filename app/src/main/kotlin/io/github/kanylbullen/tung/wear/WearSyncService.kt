package io.github.kanylbullen.tung.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import io.github.kanylbullen.tung.TungApp
import io.github.kanylbullen.tung.core.sync.SyncResult
import io.github.kanylbullen.tung.core.wear.WatchProtocol
import io.github.kanylbullen.tung.sync.SyncAccount
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives the watch's messages. The system starts it (and the app's process,
 * if needed) when a message arrives at [WatchProtocol.COMMAND_PATH] or
 * [WatchProtocol.SYNC_PATH], whether or not the app is open.
 *
 * The callback runs on a background thread and the service may be stopped as
 * soon as it returns, so the work is done before returning, under a timeout.
 */
class WearSyncService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WatchProtocol.COMMAND_PATH -> command(event)
            WatchProtocol.SYNC_PATH -> syncForWatch()
        }
    }

    private fun command(event: MessageEvent) {
        val app = application as TungApp
        val publisher = app.wearPublisher
        publisher.start()
        val text = event.data.toString(Charsets.UTF_8)
        val done = runBlocking {
            withTimeoutOrNull(TIMEOUT_MILLIS) {
                app.wearCommands.handle(text)
                publisher.publishNow()
            }
        }
        if (done == null) Log.w(TAG, "A watch command timed out")
    }

    /** A standalone watch's changes have reached the server (issue #249): fetch them now. */
    private fun syncForWatch() {
        val account = (application as TungApp).container.syncAccount
        runBlocking { syncForWatch(account, SYNC_TIMEOUT_MILLIS) }
    }

    private companion object {
        const val TAG = "WearSyncService"
        const val TIMEOUT_MILLIS = 10_000L
        const val SYNC_TIMEOUT_MILLIS = 20_000L
    }
}

/**
 * What the phone does with a standalone watch's "sync now": nothing when
 * signed out; else one sync right here, while the system keeps the service
 * (and so the process) running for the message, rather than a WorkManager job
 * that Doze or the app's standby bucket may hold back for minutes. A sync
 * that fails or outlasts [timeoutMillis] is handed to WorkManager, which
 * retries it with a network. The workout screen and the ongoing notification
 * follow Room, so a finish the sync brings ends both.
 *
 * @return the sync's result; null when signed out or timed out.
 */
internal suspend fun syncForWatch(account: SyncAccount, timeoutMillis: Long): SyncResult? {
    if (!account.isSignedIn()) return null
    val result = withTimeoutOrNull(timeoutMillis) { account.syncNow() }
    if (result !is SyncResult.Success && result != SyncResult.NotSignedIn) account.enqueueSync()
    return result
}
