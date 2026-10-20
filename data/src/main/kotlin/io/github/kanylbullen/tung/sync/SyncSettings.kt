package io.github.kanylbullen.tung.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.kanylbullen.tung.core.sync.SyncFailure
import io.github.kanylbullen.tung.core.sync.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Sync's small state in the app's Preferences DataStore (the `settings`
 * file): the server URL, the account's email, the cursor and the last result.
 * The token is not here; it is in [TokenStore].
 */
class SyncSettings(private val store: DataStore<Preferences>) {

    val serverUrl: Flow<String?> = store.data.map { it[SERVER_URL] }
    val email: Flow<String?> = store.data.map { it[EMAIL] }
    /**
     * Servers this device has signed in to, most recent first, at most
     * [RECENT_LIMIT]: what a watch offers so nobody types a URL twice.
     */
    val recentServers: Flow<List<String>> = store.data.map { prefs ->
        prefs[RECENT]?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
    }

    val state: Flow<SyncState> = store.data.map { SyncState(it[CURSOR] ?: 0L, it[LAST_SYNC_AT], it[LAST_ERROR]) }

    suspend fun serverUrl(): String? = serverUrl.first()

    suspend fun cursor(): Long = state.first().cursor

    /** A new sign-in: the server and the account, and sync starts over from cursor 0. */
    suspend fun signedIn(serverUrl: String, email: String) = store.edit {
        val recent = it[RECENT]?.split('\n')?.filter { url -> url.isNotBlank() && url != serverUrl }.orEmpty()
        it[RECENT] = (listOf(serverUrl) + recent).take(RECENT_LIMIT).joinToString("\n")
        it[SERVER_URL] = serverUrl
        it[EMAIL] = email
        it[CURSOR] = 0L
        it.remove(LAST_SYNC_AT)
        it.remove(LAST_ERROR)
    }

    /** The URL and email stay, to fill in the form next time; the result line shows [failure] if any. */
    suspend fun signedOut(failure: SyncFailure?) = store.edit {
        it.remove(LAST_SYNC_AT)
        if (failure == null) it.remove(LAST_ERROR) else it[LAST_ERROR] = failure.key
    }

    suspend fun setCursor(cursor: Long) = store.edit { it[CURSOR] = cursor }

    suspend fun recordSuccess(at: Long) = store.edit {
        it[LAST_SYNC_AT] = at
        it.remove(LAST_ERROR)
    }

    suspend fun recordFailure(failure: SyncFailure) = store.edit { it[LAST_ERROR] = failure.key }

    /**
     * This device's stable id, a random UUID made the first time it is asked
     * for: sent as `X-Tung-Device` with every sync and as `device_id` of its
     * push endpoint, so the server never pushes a change back to the device
     * that made it. Kept across sign-outs; it identifies the install, not the account.
     */
    suspend fun deviceId(): String {
        store.data.first()[DEVICE_ID]?.let { return it }
        var id = ""
        store.edit { prefs -> id = prefs[DEVICE_ID] ?: UUID.randomUUID().toString().also { prefs[DEVICE_ID] = it } }
        return id
    }

    private companion object {
        const val RECENT_LIMIT = 5
        val RECENT = stringPreferencesKey("sync_recent_servers")
        val SERVER_URL = stringPreferencesKey("sync_server_url")
        val EMAIL = stringPreferencesKey("sync_email")
        val CURSOR = longPreferencesKey("sync_cursor")
        val LAST_SYNC_AT = longPreferencesKey("sync_last_at")
        val LAST_ERROR = stringPreferencesKey("sync_last_error")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }
}
