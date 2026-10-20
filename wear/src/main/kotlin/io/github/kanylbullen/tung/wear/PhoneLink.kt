package io.github.kanylbullen.tung.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import io.github.kanylbullen.tung.core.wear.WatchCommand
import io.github.kanylbullen.tung.core.wear.WatchProtocol
import io.github.kanylbullen.tung.core.wear.WatchWorkoutState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The watch's end of the Data Layer, its [WatchBackend] in companion mode:
 * reads the phone's state DataItem and sends commands as messages.
 */
class PhoneLink(context: Context) : WatchBackend {
    private val appContext = context.applicationContext
    private val dataClient by lazy { Wearable.getDataClient(appContext) }
    private val messageClient by lazy { Wearable.getMessageClient(appContext) }
    private val nodeClient by lazy { Wearable.getNodeClient(appContext) }

    /**
     * The phone's state: first whatever the Data Layer already holds (null if
     * the phone has never published), then every change while collected.
     */
    override fun states(): Flow<WatchWorkoutState?> = callbackFlow {
        val listener = DataClient.OnDataChangedListener { events ->
            val states = events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WatchProtocol.STATE_PATH }
                .mapNotNull { decode(it.dataItem) }
            events.release()
            states.maxByOrNull { it.updatedAt }?.let { trySend(it) }
        }
        dataClient.addListener(listener)
        try {
            val items = dataClient.dataItems.awaitResult()
            val stored = items.filter { it.uri.path == WatchProtocol.STATE_PATH }.mapNotNull { decode(it) }
            items.release()
            send(stored.maxByOrNull { it.updatedAt })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the stored state", e)
            send(null)
        }
        awaitClose { dataClient.removeListener(listener) }
    }

    /** Sends [command] to every connected node (normally the one phone). True if any took it. */
    override suspend fun send(command: WatchCommand): Boolean {
        val bytes = WatchProtocol.encodeCommand(command).toByteArray(Charsets.UTF_8)
        return try {
            val nodes = nodeClient.connectedNodes.awaitResult()
            var delivered = false
            for (node in nodes) {
                try {
                    messageClient.sendMessage(node.id, WatchProtocol.COMMAND_PATH, bytes).awaitResult()
                    delivered = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not send to ${node.displayName}", e)
                }
            }
            delivered
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not list connected nodes", e)
            false
        }
    }

    private fun decode(item: DataItem): WatchWorkoutState? {
        val text = DataMapItem.fromDataItem(item).dataMap.getString(WatchProtocol.STATE_KEY) ?: return null
        return try {
            WatchProtocol.decodeState(text)
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException; a state this watch cannot read is skipped.
            Log.w(TAG, "Could not read the phone's state", e)
            null
        }
    }

    private companion object {
        const val TAG = "PhoneLink"
    }
}

/** A Play services [Task] as a suspend call. */
suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            task.isSuccessful -> continuation.resume(task.result)
            error != null -> continuation.resumeWithException(error)
            else -> continuation.cancel()
        }
    }
}
