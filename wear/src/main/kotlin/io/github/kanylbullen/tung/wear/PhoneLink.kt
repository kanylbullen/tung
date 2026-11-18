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
        // The listener and the stored read can finish in either order: only a state newer than the
        // last one sent goes out, so an older stored snapshot never overwrites a newer change.
        val lock = Any()
        var newest: Long? = null
        fun offer(state: WatchWorkoutState?) = synchronized(lock) {
            val last = newest
            when {
                state == null -> if (last == null) trySend(null)
                last == null || state.updatedAt > last -> {
                    newest = state.updatedAt
                    trySend(state)
                }
            }
        }
        val listener = DataClient.OnDataChangedListener { events ->
            val states = try {
                events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WatchProtocol.STATE_PATH }
                    .mapNotNull { decodePhoneState(it.dataItem) }
            } finally {
                events.release()
            }
            states.maxByOrNull { it.updatedAt }?.let { offer(it) }
        }
        dataClient.addListener(listener)
        try {
            val items = dataClient.dataItems.awaitResult()
            val stored = try {
                items.filter { it.uri.path == WatchProtocol.STATE_PATH }.mapNotNull { decodePhoneState(it) }
            } finally {
                items.release()
            }
            offer(stored.maxByOrNull { it.updatedAt })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the stored state", e)
            offer(null)
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

    private companion object {
        const val TAG = "PhoneLink"
    }
}

/** The phone's [WatchWorkoutState] in a state DataItem, or null when this watch cannot read it (a newer protocol). */
fun decodePhoneState(item: DataItem): WatchWorkoutState? {
    return try {
        val text = DataMapItem.fromDataItem(item).dataMap.getString(WatchProtocol.STATE_KEY) ?: return null
        WatchProtocol.decodeState(text)
    } catch (e: IllegalArgumentException) {
        // SerializationException is an IllegalArgumentException; a state this watch cannot read is skipped.
        Log.w("PhoneLink", "Could not read the phone's state", e)
        null
    } catch (e: IllegalStateException) {
        // A payload that is not a DataMap.
        Log.w("PhoneLink", "Could not read the phone's DataItem", e)
        null
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
