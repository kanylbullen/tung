package io.github.kanylbullen.tung.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.catalog.EquipmentFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * "My equipment" (CLAUDE.md, "Exercises tab and exercise page"): what the user
 * trains with, as [Equipment] keys in the `settings` DataStore. Not synced:
 * each device keeps its own, like the other settings here.
 *
 * Unset means everything, and choosing everything removes the key again, so
 * a value a later version adds is included for a user who never narrowed it.
 * A key this build does not know is ignored. An empty set is never stored:
 * the screens keep at least one ticked.
 */
class EquipmentSettings(private val store: DataStore<Preferences>) {

    val available: Flow<Set<Equipment>> =
        store.data.map { prefs -> prefs[KEY]?.let(EquipmentFilter::fromKeys)?.takeIf { it.isNotEmpty() } ?: EquipmentFilter.ALL }
            .distinctUntilChanged()

    suspend fun current(): Set<Equipment> = available.first()

    suspend fun set(value: Set<Equipment>) {
        store.edit { prefs ->
            if (value.isEmpty() || !EquipmentFilter.isNarrowed(value)) {
                prefs.remove(KEY)
            } else {
                prefs[KEY] = EquipmentFilter.toKeys(value).toSet()
            }
        }
    }

    private companion object {
        val KEY = stringSetPreferencesKey("my_equipment")
    }
}
