package io.github.kanylbullen.tung.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The device id after Android restores the `settings` file onto another
 * phone (issue #156): kept on the same device, started over on another one,
 * with the cursor and the push endpoint, and the account's own keys kept.
 */
class DeviceIdTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "settings.preferences_pb") }
    private val pushEndpoint = stringPreferencesKey("push_endpoint")
    private val cursor = longPreferencesKey("sync_cursor")

    @After
    fun close() = scope.cancel()

    private fun settings(fingerprint: String?) =
        SyncSettings(store, installFingerprint = { fingerprint }, deviceKeys = listOf(pushEndpoint))

    @Test
    fun theSameDeviceKeepsItsId() = runBlocking {
        val first = settings("phone-a").deviceId()
        assertEquals(first, settings("phone-a").deviceId())
    }

    @Test
    fun anotherDeviceStartsOverAndForgetsTheDevicesKeys() = runBlocking {
        val old = settings("phone-a")
        old.signedIn("https://tung.example.org", "lifter@example.org")
        val oldId = old.deviceId()
        store.edit {
            it[pushEndpoint] = "https://push.example.org/old-phone"
            it[cursor] = 42L
        }

        // The same file, restored onto phone B.
        val restored = settings("phone-b")
        val newId = restored.deviceId()
        assertNotEquals(oldId, newId)
        assertEquals(newId, restored.deviceId())
        val prefs = store.data.first()
        assertNull(prefs[pushEndpoint])
        assertNull(prefs[cursor])
        assertEquals("the account's keys stay", "lifter@example.org", restored.email())
    }

    @Test
    fun anIdFromBeforeFingerprintsIsKept() = runBlocking {
        val before = SyncSettings(store).deviceId()
        assertEquals(before, settings("phone-a").deviceId())
        // From then on it belongs to phone A.
        assertNotEquals(before, settings("phone-b").deviceId())
    }

    @Test
    fun withoutAFingerprintNothingChanges() = runBlocking {
        val id = settings(null).deviceId()
        store.edit { it[pushEndpoint] = "kept" }
        assertEquals(id, settings(null).deviceId())
        assertEquals("kept", store.data.first()[pushEndpoint])
    }
}
