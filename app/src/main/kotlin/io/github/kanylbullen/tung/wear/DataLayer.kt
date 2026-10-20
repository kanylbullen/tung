package io.github.kanylbullen.tung.wear

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import io.github.kanylbullen.tung.core.wear.WatchProtocol
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Where [WearStatePublisher] puts the state; the Data Layer in the app, a list in tests. */
fun interface WearStateWriter {
    suspend fun write(json: String)
}

/**
 * Writes the state as the DataItem at [WatchProtocol.STATE_PATH]. The Data
 * Layer keeps it and syncs it to the watch whenever the two are connected, so
 * a watch that was out of reach gets the latest state, not every change.
 */
class DataLayerStateWriter(context: Context) : WearStateWriter {
    private val appContext = context.applicationContext
    private val client by lazy { Wearable.getDataClient(appContext) }

    override suspend fun write(json: String) {
        val request = PutDataMapRequest.create(WatchProtocol.STATE_PATH).apply {
            dataMap.putString(WatchProtocol.STATE_KEY, json)
        }.asPutDataRequest().setUrgent()
        client.putDataItem(request).awaitResult()
    }
}

/**
 * Whether Google Play services is there to carry the Data Layer. Without it (a
 * de-Googled phone, the JVM tests) nothing wear-related is started at all.
 */
fun isWearableApiAvailable(context: Context): Boolean =
    runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

/** A Play services [Task] as a suspend call, without pulling in kotlinx-coroutines-play-services. */
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
