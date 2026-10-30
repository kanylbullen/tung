package io.github.kanylbullen.tung.health

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The two opt-ins in the `settings` DataStore, both off until the user turns
 * them on in Settings (and Health Connect grants the permissions). Not synced:
 * each phone decides for its own Health Connect.
 */
class HealthConnectSettings(private val store: DataStore<Preferences>) {

    fun enabled(access: HealthAccess): Flow<Boolean> = store.data.map { it[key(access)] ?: false }

    suspend fun setEnabled(access: HealthAccess, enabled: Boolean) {
        store.edit { it[key(access)] = enabled }
    }

    private fun key(access: HealthAccess) = when (access) {
        HealthAccess.WRITE -> WRITE
        HealthAccess.READ -> READ
    }

    private companion object {
        val WRITE = booleanPreferencesKey("health_connect_write")
        val READ = booleanPreferencesKey("health_connect_read")
    }
}
