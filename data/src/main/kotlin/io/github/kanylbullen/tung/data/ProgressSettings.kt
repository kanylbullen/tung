package io.github.kanylbullen.tung.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.kanylbullen.tung.core.progress.ProgressRange
import io.github.kanylbullen.tung.core.progress.ROW_RANGES
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The Progress tab's choice of span for its exercise rows (CLAUDE.md,
 * "Progress"): 3 or 12 months, in the `settings` DataStore under
 * `progress_rows_range`. Not synced: each device keeps its own.
 *
 * Unset, an unknown value, or a range the rows do not offer is 3 months; 3
 * months is stored as no key, so the default can change in one place.
 */
class ProgressSettings(private val store: DataStore<Preferences>) {

    val rowsRange: Flow<ProgressRange> =
        store.data.map { prefs -> fromKey(prefs[KEY]) }.distinctUntilChanged()

    suspend fun current(): ProgressRange = rowsRange.first()

    suspend fun setRowsRange(value: ProgressRange) {
        store.edit { prefs ->
            if (value == DEFAULT || value !in ROW_RANGES) prefs.remove(KEY) else prefs[KEY] = toKey(value)
        }
    }

    companion object {
        val DEFAULT = ProgressRange.THREE_MONTHS
        internal val KEY = stringPreferencesKey("progress_rows_range")

        internal fun toKey(range: ProgressRange): String = when (range) {
            ProgressRange.THREE_MONTHS -> "3m"
            ProgressRange.ONE_YEAR -> "12m"
            ProgressRange.ALL -> "all"
        }

        internal fun fromKey(key: String?): ProgressRange = when (key) {
            "12m" -> ProgressRange.ONE_YEAR
            else -> DEFAULT
        }
    }
}
