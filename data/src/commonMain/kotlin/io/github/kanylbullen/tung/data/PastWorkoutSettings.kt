package io.github.kanylbullen.tung.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The workouts this device started from "Log past workout", under
 * `past_workout_ids` in the `settings` DataStore. Such a workout is past from
 * its start, whatever its age: no rest timer, its date instead of a clock, a
 * duration on finish. Not synced: another device falls back on the workout's
 * age (`core.isPastWorkout`). An id is forgotten once its workout is finished,
 * discarded or gone on this device; one finished elsewhere stays until the
 * next is added, harmless since only the workout in progress is looked up.
 */
class PastWorkoutSettings(private val store: DataStore<Preferences>) {

    val ids: Flow<Set<String>> = store.data.map { it[KEY].orEmpty() }.distinctUntilChanged()

    /** Remembers [workoutId]; there is at most one workout in progress, so it replaces any other. */
    suspend fun add(workoutId: String) {
        store.edit { it[KEY] = setOf(workoutId) }
    }

    suspend fun forget(workoutId: String) {
        store.edit { prefs ->
            val left = prefs[KEY].orEmpty() - workoutId
            if (left.isEmpty()) prefs.remove(KEY) else prefs[KEY] = left
        }
    }

    private companion object {
        val KEY = stringSetPreferencesKey("past_workout_ids")
    }
}
