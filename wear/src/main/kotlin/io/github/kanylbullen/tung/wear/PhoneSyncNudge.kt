package io.github.kanylbullen.tung.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import io.github.kanylbullen.tung.core.wear.WatchProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Tells the paired phone to sync now; the Data Layer in the app, a counter in tests. */
fun interface PhoneNudger {
    /** True if a phone took the message. */
    suspend fun nudge(): Boolean
}

/**
 * Nudges the phone after a sync that carried the watch's changes up
 * (`SyncClient`'s `onPushed`, wired in `TungWearApp`), so a workout finished
 * or changed on a standalone watch reaches the phone at once, push or no
 * push (issue #249). Only a signed-in watch pushes anything, so a watch in
 * companion mode never sends it. The sync awaits it, so a WorkManager job
 * keeps the process until the message is out; [timeoutMillis] bounds that,
 * and a failure is logged and never fails the sync: the next one tries again.
 *
 * @return true if a phone took the message.
 */
suspend fun nudgePhone(nudger: PhoneNudger, timeoutMillis: Long = NUDGE_TIMEOUT_MILLIS): Boolean =
    try {
        withTimeoutOrNull(timeoutMillis) { nudger.nudge() } ?: false.also { Log.w(TAG, "Nudging the phone timed out") }
    } catch (e: CancellationException) {
        // A Play services task that was cancelled surfaces as one too: only the caller's cancellation goes on.
        currentCoroutineContext().ensureActive()
        Log.w(TAG, "Nudging the phone was cancelled", e)
        false
    } catch (e: Exception) {
        Log.w(TAG, "Could not nudge the phone", e)
        false
    }

/** Enough for the Data Layer over Bluetooth; a sync is not held up longer. */
const val NUDGE_TIMEOUT_MILLIS = 5_000L

/**
 * Sends the empty [WatchProtocol.SYNC_PATH] message to every connected node
 * (normally the one phone). An older phone has no listener for the path and
 * drops it. Nothing in it but the path: the data goes through the server.
 */
class DataLayerPhoneNudger(context: Context) : PhoneNudger {
    private val appContext = context.applicationContext
    private val messageClient by lazy { Wearable.getMessageClient(appContext) }
    private val nodeClient by lazy { Wearable.getNodeClient(appContext) }

    override suspend fun nudge(): Boolean {
        val nodes = nodeClient.connectedNodes.awaitResult()
        var delivered = false
        for (node in nodes) {
            try {
                messageClient.sendMessage(node.id, WatchProtocol.SYNC_PATH, ByteArray(0)).awaitResult()
                delivered = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not nudge ${node.displayName}", e)
            }
        }
        return delivered
    }
}

private const val TAG = "PhoneSyncNudge"
