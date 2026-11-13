package io.github.kanylbullen.tung.push

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.kanylbullen.tung.core.sync.PushChoice
import io.github.kanylbullen.tung.core.sync.PushTransport
import io.github.kanylbullen.tung.sync.SyncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Push on the iPhone: the Apple Push Notification service, the iPhone's only
 * transport. iOS hands the app a device token ([onToken], from the app
 * delegate); it is kept in [store] and registered with the server as an
 * `apns` endpoint while signed in, so the server's silent push wakes the app
 * for a sync, as FCM wakes the phone (the phone's `PushRegistrar`). A server
 * without APNs, or an older one that answers 400 to `apns`, leaves the
 * iPhone on its own schedule; nothing else changes.
 */
class IosPush(
    private val client: SyncClient,
    private val store: DataStore<Preferences>,
    private val signedIn: Flow<Boolean?>,
    private val scope: CoroutineScope,
) : PushControl {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(PushStatus(choice = PushChoice.Apns))
    override val status: StateFlow<PushStatus> = _status.asStateFlow()

    private var started = false

    /** The token this process has registered with the server, so the same one is not sent twice. */
    private var registered: String? = null

    /** Follows the account: registers the token whenever it is signed in (app start, sign-in). */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            signedIn.distinctUntilChanged().collect { if (it == true) refresh() else registered = null }
        }
    }

    /** A device token from iOS (every launch, and a new one after a restore or a reinstall), as hex. */
    suspend fun onToken(token: String) {
        mutex.withLock { store.edit { it[TOKEN] = token } }
        refresh()
    }

    /** Makes sure the server has the stored token while signed in. */
    override suspend fun refresh() {
        mutex.withLock {
            val token = store.data.first()[TOKEN] ?: return
            _status.value = _status.value.copy(endpoint = token)
            if (signedIn.first() != true || registered == token) return
            val id = client.registerPushEndpoint(PushTransport.APNS, token)
            if (id == null) {
                println("Tung push: the server did not take the apns endpoint; sync stays on the app's own schedule")
                return
            }
            store.edit { it[ENDPOINT_ID] = id }
            registered = token
            _status.value = _status.value.copy(registered = true)
        }
    }

    /** One transport, nothing to choose. */
    override suspend fun chooseDistributor(packageName: String) = Unit

    /** Before signing out, with the session still valid: the server forgets this iPhone's endpoint. */
    suspend fun onSignOut(serverUrl: String, token: String) {
        mutex.withLock {
            store.data.first()[ENDPOINT_ID]?.let { client.deletePushEndpoint(serverUrl, token, it) }
            store.edit { it.remove(ENDPOINT_ID) }
            registered = null
            _status.value = _status.value.copy(registered = false)
        }
    }

}

private val TOKEN = stringPreferencesKey("push_endpoint")
private val ENDPOINT_ID = stringPreferencesKey("push_endpoint_id")

/** The iPhone's push keys, forgotten with the device id after a restore onto another iPhone (issue #156). */
internal val IOS_PUSH_DEVICE_KEYS: List<Preferences.Key<*>> = listOf(TOKEN, ENDPOINT_ID)
