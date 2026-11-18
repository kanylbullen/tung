package io.github.kanylbullen.tung.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import io.github.kanylbullen.tung.core.wear.WatchProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate

/** Tells the paired phone to sync now; the Data Layer in the app, a counter in tests. */
fun interface PhoneNudger {
    /** True if a phone took the message. */
    suspend fun nudge(): Boolean
}

/**
 * Follows [pushes] (the watch's `SyncClient.pushes`: a sync that carried the
 * watch's changes up) and nudges the phone after each, so a workout finished
 * or changed on a standalone watch reaches the phone at once, push or no
 * push (issue #249). Only a signed-in watch pushes anything, so a watch in
 * companion mode never sends it. Conflated: syncs that finish while a nudge
 * is on its way share the next one. Runs until cancelled; a failed send is
 * logged and the next sync tries again.
 */
suspend fun nudgePhoneAfterPushes(pushes: Flow<Unit>, nudger: PhoneNudger) {
    pushes.conflate().collect {
        try {
            nudger.nudge()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not nudge the phone", e)
        }
    }
}

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
