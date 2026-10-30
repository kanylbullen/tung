package io.github.kanylbullen.tung.importing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The last import on this device, so Settings can undo it after the summary is gone. */
data class LastImport(
    val source: String,
    val batchId: String,
    val routineIds: List<String>,
    val workouts: Int,
    val at: Long,
)

/**
 * Remembers the last import ([LastImport]) in the `settings` DataStore. Only
 * the ids undo needs; the workouts carry their batch themselves. Not synced:
 * undo is for the device that imported.
 */
class ImportHistory(private val store: DataStore<Preferences>) {

    val last: Flow<LastImport?> = store.data.map { p ->
        val batch = p[BATCH] ?: return@map null
        LastImport(
            source = p[SOURCE].orEmpty(),
            batchId = batch,
            routineIds = p[ROUTINES].orEmpty().split(',').filter { it.isNotEmpty() },
            workouts = p[WORKOUTS] ?: 0,
            at = p[AT] ?: 0L,
        )
    }

    suspend fun remember(source: String, result: ImportResult, at: Long) = store.edit {
        it[SOURCE] = source
        it[BATCH] = result.batchId
        it[ROUTINES] = result.routineIds.joinToString(",")
        it[WORKOUTS] = result.workoutsAdded
        it[AT] = at
    }

    /** Forgets [batchId] if it is the last import. */
    suspend fun forget(batchId: String) = store.edit {
        if (it[BATCH] == batchId) {
            it.remove(SOURCE)
            it.remove(BATCH)
            it.remove(ROUTINES)
            it.remove(WORKOUTS)
            it.remove(AT)
        }
    }

    private companion object {
        val SOURCE = stringPreferencesKey("import_last_source")
        val BATCH = stringPreferencesKey("import_last_batch")
        val ROUTINES = stringPreferencesKey("import_last_routines")
        val WORKOUTS = intPreferencesKey("import_last_workouts")
        val AT = longPreferencesKey("import_last_at")
    }
}
