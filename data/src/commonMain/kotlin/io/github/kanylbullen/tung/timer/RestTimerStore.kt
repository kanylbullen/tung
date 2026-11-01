package io.github.kanylbullen.tung.timer

import io.github.kanylbullen.tung.core.RestTimer
import kotlinx.coroutines.flow.Flow

/**
 * Where the active workout keeps its rest timer between processes, and what
 * happens when a rest ends: the workout screen's view of the platform's timer.
 * The phone's and a standalone watch's is `RestTimerController` (DataStore and
 * an alarm); the countdown itself is core's [RestTimer].
 */
interface RestTimerStore {
    /** The rest for exercises without their own setting. */
    val defaultSeconds: Flow<Int>

    /** The timer stored for [workoutId], or null if none is (or it belongs to another workout). */
    suspend fun stored(workoutId: String): RestTimer?

    /** Makes [timer] the rest timer of [workoutId], or clears it for null, with the alarm that ends it. */
    suspend fun set(workoutId: String, timer: RestTimer?)

    /** True the first time it is called, so the notification permission is asked for once. */
    suspend fun firstTimeAskingForNotifications(): Boolean

    /** The rest is over while the workout is on screen: the same alert as the alarm's. */
    fun restOver(workoutId: String, endsAtMillis: Long)
}
