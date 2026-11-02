package io.github.kanylbullen.tung.timer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.kanylbullen.tung.core.DEFAULT_REST_SECONDS
import io.github.kanylbullen.tung.core.MAX_REST_SECONDS
import io.github.kanylbullen.tung.core.RestTimer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The rest timer as it is kept between processes, in the `settings`
 * DataStore: the default rest, the running timer and whether the
 * notification permission was asked for. Every platform's [RestTimerStore]
 * keeps it here and adds its own alarm and alert (`RestTimerController` on
 * Android, `IosRestTimer` on the iPhone).
 */
class StoredRestTimer(private val settings: DataStore<Preferences>) {

    /** The rest for exercises without their own setting; Settings' Training sets it ([setDefaultSeconds]). */
    val defaultSeconds: Flow<Int> = settings.data.map { it[DEFAULT_SECONDS] ?: DEFAULT_REST_SECONDS }

    /** Stores the default rest, clamped to 0 (off) .. [MAX_REST_SECONDS]. */
    suspend fun setDefaultSeconds(seconds: Int) {
        settings.edit { it[DEFAULT_SECONDS] = seconds.coerceIn(0, MAX_REST_SECONDS) }
    }

    /** The timer stored for [workoutId], or null if none is (or it belongs to another workout). */
    suspend fun stored(workoutId: String): RestTimer? {
        val prefs = settings.data.first()
        if (prefs[WORKOUT_ID] != workoutId) return null
        val endsAt = prefs[ENDS_AT] ?: return null
        return RestTimer(endsAtMillis = endsAt, durationMillis = prefs[DURATION] ?: 0L)
    }

    /** Stores [timer] as the rest timer of [workoutId], or clears it for null. */
    suspend fun save(workoutId: String, timer: RestTimer?) {
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
     * for once and never nagged about after that. The workout screen asks the
     * first time it shows a workout.
     */
    suspend fun firstTimeAskingForNotifications(): Boolean {
        var first = false
        settings.edit { prefs ->
            first = prefs[NOTIFICATIONS_ASKED] != true
            prefs[NOTIFICATIONS_ASKED] = true
        }
        return first
    }

    private companion object {
        val DEFAULT_SECONDS = intPreferencesKey("rest_default_seconds")
        val WORKOUT_ID = stringPreferencesKey("rest_workout_id")
        val ENDS_AT = longPreferencesKey("rest_ends_at")
        val DURATION = longPreferencesKey("rest_duration")
        val NOTIFICATIONS_ASKED = booleanPreferencesKey("rest_notifications_asked")
    }
}
