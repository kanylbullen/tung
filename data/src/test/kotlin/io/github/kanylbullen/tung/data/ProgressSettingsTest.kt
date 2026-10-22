package io.github.kanylbullen.tung.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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

/** The Progress tab's row span in the `settings` DataStore: 3 months by default, 12 months kept across a restart. */
class ProgressSettingsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val jobs = mutableListOf<Job>()
    private val file by lazy { File(tmp.root, "settings.preferences_pb") }
    private val key = stringPreferencesKey("progress_rows_range")

    /** A fresh DataStore over the same file, as after the app is restarted. */
    private fun open(): Pair<ProgressSettings, DataStore<Preferences>> {
        val job = SupervisorJob().also { jobs += it }
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        return ProgressSettings(store) to store
    }

    @After
    fun stop() = jobs.forEach { it.cancel() }

    @Test
    fun threeMonthsByDefault() = runBlocking {
        val (settings, _) = open()
        assertEquals(ProgressRange.THREE_MONTHS, settings.current())
    }

    @Test
    fun twelveMonthsIsKeptAcrossARestart() = runBlocking {
        val (settings, store) = open()
        settings.setRowsRange(ProgressRange.ONE_YEAR)
        assertEquals(ProgressRange.ONE_YEAR, settings.rowsRange.first())
        assertEquals("12m", store.data.first()[key])
        jobs.removeAt(0).cancelAndJoin()

        val (again, _) = open()
        assertEquals(ProgressRange.ONE_YEAR, again.current())
    }

    /** Back to 3 months removes the key; a range the rows do not offer, or an unknown value, reads as 3 months. */
    @Test
    fun theDefaultIsNoKeyAndUnknownValuesFallBack() = runBlocking {
        val (settings, store) = open()
        settings.setRowsRange(ProgressRange.ONE_YEAR)
        settings.setRowsRange(ProgressRange.THREE_MONTHS)
        assertNull(store.data.first()[key])
        settings.setRowsRange(ProgressRange.ALL)
        assertNull(store.data.first()[key])
        assertEquals(ProgressRange.THREE_MONTHS, settings.current())
        store.edit { it[key] = "5y" }
        assertEquals(ProgressRange.THREE_MONTHS, settings.current())
    }
}
