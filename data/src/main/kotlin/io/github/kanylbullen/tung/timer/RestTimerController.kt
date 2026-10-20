package io.github.kanylbullen.tung.timer

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.kanylbullen.tung.core.DEFAULT_REST_SECONDS
import io.github.kanylbullen.tung.core.RestTimer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The rest timer's Android side: where it is kept between processes
 * ([settings], a Preferences DataStore), the alarm that ends it ([RestAlarm])
 * and what happens when it ends ([RestAlerts]). The countdown itself is
 * [RestTimer] in `core`; the active workout's ViewModel owns the current value.
 */
class RestTimerController(
    private val context: Context,
    private val settings: DataStore<Preferences>,
) {

    /** The rest for exercises without their own setting. No screen changes it yet. */
    val defaultSeconds: Flow<Int> = settings.data.map { it[DEFAULT_SECONDS] ?: DEFAULT_REST_SECONDS }

    /** The timer stored for [workoutId], or null if none is (or it belongs to another workout). */
    suspend fun stored(workoutId: String): RestTimer? {
        val prefs = settings.data.first()
        if (prefs[WORKOUT_ID] != workoutId) return null
        val endsAt = prefs[ENDS_AT] ?: return null
        return RestTimer(endsAtMillis = endsAt, durationMillis = prefs[DURATION] ?: 0L)
    }

    /**
     * Makes [timer] the rest timer of [workoutId]: sets (or, for null, cancels)
     * the alarm that ends it and stores it. A new or cleared timer also takes
     * away a "Rest over" notification still showing from the last one.
     */
    suspend fun set(workoutId: String, timer: RestTimer?) {
        RestAlerts.cancelNotification(context)
        if (timer == null) RestAlarm.cancel(context) else RestAlarm.schedule(context, workoutId, timer.endsAtMillis)
        settings.edit { prefs ->
            if (timer == null) {
                prefs.remove(WORKOUT_ID)
                prefs.remove(ENDS_AT)
                prefs.remove(DURATION)
            } else {
                prefs[WORKOUT_ID] = workoutId
                prefs[ENDS_AT] = timer.endsAtMillis
                prefs[DURATION] = timer.durationMillis
            }
        }
    }

    /**
     * True the first time it is called, so the notification permission is asked
     * for once, when the first timer starts, and never nagged about after that.
     */
    suspend fun firstTimeAskingForNotifications(): Boolean {
        var first = false
        settings.edit { prefs ->
            first = prefs[NOTIFICATIONS_ASKED] != true
            prefs[NOTIFICATIONS_ASKED] = true
        }
        return first
    }

    /** The rest is over while the workout is on screen: the same alert as the alarm's. */
    fun restOver(workoutId: String, endsAtMillis: Long) = RestAlerts.restOver(context, workoutId, endsAtMillis)

    private companion object {
        val DEFAULT_SECONDS = intPreferencesKey("rest_default_seconds")
        val WORKOUT_ID = stringPreferencesKey("rest_workout_id")
        val ENDS_AT = longPreferencesKey("rest_ends_at")
        val DURATION = longPreferencesKey("rest_duration")
        val NOTIFICATIONS_ASKED = booleanPreferencesKey("rest_notifications_asked")
    }
}
