package io.github.kanylbullen.tung.web.sync

import io.github.kanylbullen.tung.core.newId
import io.github.kanylbullen.tung.core.sync.SyncFailure
import io.github.kanylbullen.tung.core.sync.SyncState
import io.github.kanylbullen.tung.web.store.IndexedDbStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The server this browser is signed in to, and as whom. */
data class Account(val serverUrl: String, val email: String)

/**
 * What the phone keeps in its `settings` DataStore and its token store, here
 * in IndexedDB's `meta`: the server, the email, the session token, the
 * cursor, the last result, and the device id. Read once at start into
 * memory ([load]); every change is written through.
 *
 * The token lives in IndexedDB like the rest: a browser has no Keystore,
 * and any script of this origin could read it wherever it was put.
 */
class WebSettings(private val db: IndexedDbStore) {
    private val _account = MutableStateFlow<Account?>(null)
    private val _syncState = MutableStateFlow(SyncState())

    /** The signed-in account, or null; the screens switch on it. */
    val account: StateFlow<Account?> = _account.asStateFlow()

    /** The cursor, the last success and the last failure, core's [SyncState]. */
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    /** The session token, or null when signed out. Never shown, never logged. */
    var token: String? = null
        private set

    /** The server the sign-in form starts with: the last one used, else null. */
    var lastServerUrl: String? = null
        private set

    /** The email the sign-in form starts with. */
    var lastEmail: String? = null
        private set

    suspend fun load() {
        lastServerUrl = db.meta(SERVER)
        lastEmail = db.meta(EMAIL)
        token = db.meta(TOKEN)
        _syncState.value = SyncState(
            cursor = db.meta(CURSOR)?.toLongOrNull() ?: 0,
            lastSyncAt = db.meta(LAST_SYNC_AT)?.toLongOrNull(),
            lastError = db.meta(LAST_ERROR),
        )
        val server = lastServerUrl
        val email = lastEmail
        _account.value = if (token != null && server != null && email != null) Account(server, email) else null
    }

    /** This browser's stable id for [io.github.kanylbullen.tung.core.sync.DEVICE_HEADER], made once and kept across sign-outs. */
    suspend fun deviceId(): String {
        db.meta(DEVICE_ID)?.let { return it }
        val id = newId()
        db.putMeta(DEVICE_ID, id)
        return id
    }

    /** A new session: the token is kept, the cursor starts at 0 so the account's whole log comes down. */
    suspend fun signedIn(serverUrl: String, email: String, token: String) {
        db.putMeta(SERVER, serverUrl)
        db.putMeta(EMAIL, email)
        db.putMeta(TOKEN, token)
        db.putMeta(CURSOR, "0")
        db.putMeta(LAST_ERROR, null)
        this.token = token
        lastServerUrl = serverUrl
        lastEmail = email
        _syncState.value = _syncState.value.copy(cursor = 0, lastError = null)
        _account.value = Account(serverUrl, email)
    }

    /** The token goes; the server and email stay for the next sign-in. [failure] is shown on the sign-in screen (a 401), null for the user's own sign-out. */
    suspend fun signedOut(failure: SyncFailure?) {
        db.putMeta(TOKEN, null)
        db.putMeta(LAST_ERROR, failure?.key)
        token = null
        _syncState.value = _syncState.value.copy(lastError = failure?.key)
        _account.value = null
    }

    suspend fun setCursor(cursor: Long) {
        db.putMeta(CURSOR, cursor.toString())
        _syncState.value = _syncState.value.copy(cursor = cursor)
    }

    suspend fun recordSuccess(now: Long) {
        db.putMeta(LAST_SYNC_AT, now.toString())
        db.putMeta(LAST_ERROR, null)
        _syncState.value = _syncState.value.copy(lastSyncAt = now, lastError = null)
    }

    suspend fun recordFailure(failure: SyncFailure) {
        db.putMeta(LAST_ERROR, failure.key)
        _syncState.value = _syncState.value.copy(lastError = failure.key)
    }

    private companion object {
        const val SERVER = "server_url"
        const val EMAIL = "email"
        const val TOKEN = "token"
        const val CURSOR = "cursor"
        const val LAST_SYNC_AT = "last_sync_at"
        const val LAST_ERROR = "last_error"
        const val DEVICE_ID = "device_id"
    }
}
