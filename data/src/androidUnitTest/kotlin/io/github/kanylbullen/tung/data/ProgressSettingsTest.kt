package io.github.kanylbullen.tung.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.kanylbullen.tung.core.progress.ProgressMetric
import io.github.kanylbullen.tung.core.progress.ProgressRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The compact trends' span and metric in the `settings` DataStore: 3 months of
 * estimated 1RM by default, a choice kept across a restart.
 */
class ProgressSettingsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val jobs = mutableListOf<Job>()
    private val file by lazy { File(tmp.root, "settings.preferences_pb") }
    private val key = stringPreferencesKey("progress_rows_range")
    private val metricKey = stringPreferencesKey("progress_rows_metric")

    /** A fresh DataStore over the same file, as after the app is restarted. */
    private fun open(): Pair<ProgressSettings, DataStore<Preferences>> {
        val job = SupervisorJob().also { jobs += it }
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        return ProgressSettings(store) to store
    }

    @After
    fun stop() = jobs.forEach { it.cancel() }

    @Test
    fun threeMonthsOfEstimatesByDefault() = runBlocking {
        val (settings, _) = open()
        assertEquals(ProgressRange.THREE_MONTHS, settings.trendRange.first())
        assertEquals(ProgressMetric.E1RM, settings.trendMetric.first())
    }

    @Test
    fun twelveMonthsIsKeptAcrossARestart() = runBlocking {
        val (settings, store) = open()
        settings.setTrendRange(ProgressRange.ONE_YEAR)
        settings.setTrendMetric(ProgressMetric.WEEKLY_VOLUME)
        assertEquals(ProgressRange.ONE_YEAR, settings.trendRange.first())
        assertEquals(ProgressMetric.WEEKLY_VOLUME, settings.trendMetric.first())
        assertEquals("12m", store.data.first()[key])
        assertEquals("weekly_volume", store.data.first()[metricKey])
        jobs.removeAt(0).cancelAndJoin()

        val (again, _) = open()
        assertEquals(ProgressRange.ONE_YEAR, again.trendRange.first())
        assertEquals(ProgressMetric.WEEKLY_VOLUME, again.trendMetric.first())
        again.setTrendMetric(ProgressMetric.VOLUME)
        assertEquals(ProgressMetric.VOLUME, again.trendMetric.first())
    }

    /** Back to the default removes the key; a choice the trends do not offer, or an unknown value, reads as the default. */
    @Test
    fun theDefaultIsNoKeyAndUnknownValuesFallBack() = runBlocking {
        val (settings, store) = open()
        settings.setTrendRange(ProgressRange.ONE_YEAR)
        settings.setTrendRange(ProgressRange.THREE_MONTHS)
        assertNull(store.data.first()[key])
        settings.setTrendRange(ProgressRange.ALL)
        assertNull(store.data.first()[key])
        assertEquals(ProgressRange.THREE_MONTHS, settings.trendRange.first())
        store.edit { it[key] = "5y" }
        assertEquals(ProgressRange.THREE_MONTHS, settings.trendRange.first())

        settings.setTrendMetric(ProgressMetric.VOLUME)
        settings.setTrendMetric(ProgressMetric.E1RM)
        assertNull(store.data.first()[metricKey])
        settings.setTrendMetric(ProgressMetric.TOP_SET)
        assertNull(store.data.first()[metricKey])
        assertEquals(ProgressMetric.E1RM, settings.trendMetric.first())
        store.edit { it[metricKey] = "sets_per_week" }
        assertEquals(ProgressMetric.E1RM, settings.trendMetric.first())
    }
}
