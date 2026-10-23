package io.github.kanylbullen.tung.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.kanylbullen.tung.core.progress.ProgressMetric
import io.github.kanylbullen.tung.core.progress.ProgressRange
import io.github.kanylbullen.tung.core.progress.TREND_METRICS
import io.github.kanylbullen.tung.core.progress.TREND_RANGES
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The compact trends' choices (CLAUDE.md, "Progress"), shared by the Progress
 * tab's exercise rows and the progress sheet inside a workout: the span, 3 or
 * 12 months, under `progress_rows_range`, and the metric, estimated 1RM or
 * volume per session or per week, under `progress_rows_metric`, both in the
 * `settings` DataStore. Not synced: each device keeps its own.
 *
 * Unset, an unknown value, or a choice the trends do not offer is the
 * default (3 months, estimated 1RM), and the default is stored as no key, so
 * it can change in one place.
 */
class ProgressSettings(private val store: DataStore<Preferences>) {

    val trendRange: Flow<ProgressRange> =
        store.data.map { prefs -> rangeFromKey(prefs[RANGE_KEY]) }.distinctUntilChanged()

    val trendMetric: Flow<ProgressMetric> =
        store.data.map { prefs -> metricFromKey(prefs[METRIC_KEY]) }.distinctUntilChanged()

    suspend fun setTrendRange(value: ProgressRange) {
        store.edit { prefs ->
            if (value == DEFAULT_RANGE || value !in TREND_RANGES) prefs.remove(RANGE_KEY) else prefs[RANGE_KEY] = rangeKey(value)
        }
    }

    suspend fun setTrendMetric(value: ProgressMetric) {
        store.edit { prefs ->
            if (value == DEFAULT_METRIC || value !in TREND_METRICS) prefs.remove(METRIC_KEY) else prefs[METRIC_KEY] = metricKey(value)
        }
    }

    companion object {
        val DEFAULT_RANGE = ProgressRange.THREE_MONTHS
        val DEFAULT_METRIC = ProgressMetric.E1RM
        internal val RANGE_KEY = stringPreferencesKey("progress_rows_range")
        internal val METRIC_KEY = stringPreferencesKey("progress_rows_metric")

        internal fun rangeKey(range: ProgressRange): String = when (range) {
            ProgressRange.THREE_MONTHS -> "3m"
            ProgressRange.ONE_YEAR -> "12m"
            ProgressRange.ALL -> "all"
        }

        internal fun rangeFromKey(key: String?): ProgressRange = when (key) {
            "12m" -> ProgressRange.ONE_YEAR
            else -> DEFAULT_RANGE
        }

        internal fun metricKey(metric: ProgressMetric): String = when (metric) {
            ProgressMetric.E1RM -> "e1rm"
            ProgressMetric.TOP_SET -> "top_set"
            ProgressMetric.VOLUME -> "session_volume"
            ProgressMetric.WEEKLY_VOLUME -> "weekly_volume"
        }

        internal fun metricFromKey(key: String?): ProgressMetric = when (key) {
            "session_volume" -> ProgressMetric.VOLUME
            "weekly_volume" -> ProgressMetric.WEEKLY_VOLUME
            else -> DEFAULT_METRIC
        }
    }
}
