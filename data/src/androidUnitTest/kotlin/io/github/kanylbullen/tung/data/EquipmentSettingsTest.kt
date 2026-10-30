package io.github.kanylbullen.tung.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.catalog.EquipmentFilter
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

/** "My equipment" in the `settings` DataStore: everything by default, kept across a restart, everything stored as no key. */
class EquipmentSettingsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val jobs = mutableListOf<Job>()
    private val file by lazy { File(tmp.root, "settings.preferences_pb") }

    /** A fresh DataStore over the same file, as after the app is restarted. */
    private fun open(): Pair<EquipmentSettings, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>> {
        val job = SupervisorJob().also { jobs += it }
        val scope = CoroutineScope(Dispatchers.IO + job)
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        return EquipmentSettings(store) to store
    }

    @After
    fun stop() = jobs.forEach { it.cancel() }

    @Test
    fun everythingByDefault() = runBlocking {
        val (settings, _) = open()
        assertEquals(EquipmentFilter.ALL, settings.current())
    }

    @Test
    fun aNarrowedSetIsKeptAcrossARestart() = runBlocking {
        val (settings, store) = open()
        settings.set(setOf(Equipment.BODYWEIGHT, Equipment.KETTLEBELL))
        assertEquals(setOf(Equipment.BODYWEIGHT, Equipment.KETTLEBELL), settings.available.first())
        assertEquals(setOf("kettlebell", "bodyweight"), store.data.first()[stringSetPreferencesKey("my_equipment")])
        // Closed, as when the process ends: DataStore allows one instance per file at a time.
        jobs.removeAt(0).cancelAndJoin()

        val (again, _) = open()
        assertEquals(setOf(Equipment.BODYWEIGHT, Equipment.KETTLEBELL), again.current())
    }

    /** Everything (and an empty set, which the screens never send) removes the key, so a value added later is included. */
    @Test
    fun everythingIsStoredAsNoKey() = runBlocking {
        val (settings, store) = open()
        settings.set(EquipmentFilter.BODYWEIGHT_ONLY)
        settings.set(EquipmentFilter.ALL)
        assertNull(store.data.first()[stringSetPreferencesKey("my_equipment")])
        assertEquals(EquipmentFilter.ALL, settings.current())
        settings.set(emptySet())
        assertEquals(EquipmentFilter.ALL, settings.current())
    }
}
