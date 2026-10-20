package io.github.kanylbullen.tung.wear

import android.content.Context
import android.util.Log
import io.github.kanylbullen.tung.core.wear.WatchProtocol
import io.github.kanylbullen.tung.core.wear.WatchWorkoutState
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.timer.RestTimerController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mirrors the active workout to a watch in companion mode: the state of
 * [WatchStateSource] (in `data/`, shared with a standalone watch, which shows
 * its own database the same way), written through [writer] on every change,
 * debounced by [DEBOUNCE_MILLIS].
 *
 * It runs for as long as the app's process does: started from
 * [io.github.kanylbullen.tung.TungApp] when Play services is present, and
 * again (a no-op then) by [WearSyncService] when the system starts the process
 * for a command, which also publishes once right after applying it
 * ([publishNow]). When Android kills the idle process, nothing publishes until
 * it runs again; the DataItem keeps the last state meanwhile, and the phone
 * cannot change the workout without the process running anyway.
 *
 * @param restChanges emits whenever the stored rest timer may have changed
 *   (the settings DataStore's data); the timer itself is read through [restTimer].
 * @param serverUrl the server the phone is signed in to, published so a
 *   standalone watch can link to the same one without typing a URL.
 */
@OptIn(FlowPreview::class)
class WearStatePublisher(
    context: Context,
    workouts: WorkoutRepository,
    routines: RoutineRepository,
    restTimer: RestTimerController,
    restChanges: Flow<Any?>,
    private val writer: WearStateWriter,
    serverUrl: Flow<String?> = flowOf(null),
    clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val source = WatchStateSource(context, workouts, routines, restTimer, restChanges, WearSelectionHolder(), serverUrl, clock)

    /** The watch's current-set selection, set by [WearCommandHandler]. */
    val selection: WearSelectionHolder get() = source.selection

    private val started = AtomicBoolean(false)
    private val writeLock = Mutex()

    /** The last state written, with `updatedAt` zeroed: an unchanged state is not written again. */
    private var lastWritten: WatchWorkoutState? = null

    /** Starts observing and publishing. Calling it again does nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            source.states().debounce(DEBOUNCE_MILLIS).collect { write(it) }
        }
    }

    /** Builds the current state and writes it now, without waiting for the observer. */
    suspend fun publishNow() = write(snapshot())

    /** The state as it is right now. */
    suspend fun snapshot(): WatchWorkoutState = source.states().first()

    private suspend fun write(state: WatchWorkoutState) = writeLock.withLock {
        val comparable = state.copy(updatedAt = 0L)
        if (comparable == lastWritten) return@withLock
        try {
            writer.write(WatchProtocol.encodeState(state))
            lastWritten = comparable
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No watch app, no Wear OS app on the phone, or the API is unavailable: not an error for the phone.
            Log.w(TAG, "Could not publish the workout to the watch", e)
        }
    }

    companion object {
        const val DEBOUNCE_MILLIS = 100L
        private const val TAG = "WearStatePublisher"
    }
}
