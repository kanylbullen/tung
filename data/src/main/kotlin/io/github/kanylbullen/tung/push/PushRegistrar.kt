package io.github.kanylbullen.tung.push

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.kanylbullen.tung.core.sync.PushChoice
import io.github.kanylbullen.tung.core.sync.PushTransport
import io.github.kanylbullen.tung.core.sync.selectPushTransport
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

/** An installed UnifiedPush distributor, for Settings' "Choose UnifiedPush distributor". */
data class PushDistributor(val packageName: String, val label: String)

/**
 * What Settings shows about push. [choice] is null until the first
 * [PushRegistrar.refresh]; [registered] is true once the server has the
 * endpoint; [distributors] are the installed ones, to choose among when
 * there are several.
 */
data class PushStatus(
    val choice: PushChoice? = null,
    val distributorLabel: String? = null,
    val registered: Boolean = false,
    val distributors: List<PushDistributor> = emptyList(),
    /** The endpoint the server has: an FCM token or a UnifiedPush URL (show only its host). */
    val endpoint: String? = null,
)

/**
 * Push on this device (issue #34): picks the transport ([selectPushTransport]),
 * registers its endpoint with the server under this device's id and keeps it
 * current, and takes it away at sign-out. The server only ever sends an empty
 * "sync now"; receiving one ([TungFirebaseMessagingService],
 * [TungUnifiedPushReceiver]) enqueues a sync.
 *
 * Started by the phone ([start], while signed in it refreshes at app start
 * and at every sign-in); the watch never starts it: neither transport reaches
 * a standalone watch reliably, so it keeps the periodic sync.
 */
class PushRegistrar(
    private val environment: PushEnvironment,
    private val client: SyncClient,
    private val store: DataStore<Preferences>,
    private val signedIn: Flow<Boolean?>,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(PushStatus())
    val status: StateFlow<PushStatus> = _status.asStateFlow()

    @Volatile private var started = false

    /** What this process has registered with the server, so a repeated endpoint is not sent twice. */
    @Volatile private var registered: Pair<String, String>? = null

    /** Follows the account: a refresh whenever it is signed in (app start, sign-in), nothing while signed out. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            signedIn.distinctUntilChanged().collect { if (it == true) refresh() else registered = null }
        }
    }

    /**
     * Picks the transport again and makes sure the server has its endpoint:
     * FCM's token at once, a UnifiedPush endpoint when the distributor answers
     * ([onNewEndpoint]; registering again at every start is how the connector
     * is meant to be used). A transport given up is unregistered.
     */
    suspend fun refresh() {
        val choice = mutex.withLock {
            val distributors = environment.distributors()
            val choice = selectPushTransport(
                environment.playServicesAvailable(),
                environment.firebaseConfigured(),
                distributors,
                store.data.first()[CHOSEN_DISTRIBUTOR],
            )
            val previous = store.data.first()[TRANSPORT]
            if (previous == PushTransport.UNIFIED_PUSH && choice !is PushChoice.UnifiedPush) environment.unregisterUnifiedPush()
            store.edit {
                if (choice.transport != previous) {
                    it.remove(ENDPOINT)
                    registered = null
                }
                val transport = choice.transport
                if (transport == null) it.remove(TRANSPORT) else it[TRANSPORT] = transport
            }
            _status.value = PushStatus(
                choice = choice,
                distributorLabel = (choice as? PushChoice.UnifiedPush)?.let { environment.distributorLabel(it.distributor) },
                registered = _status.value.registered && choice == _status.value.choice,
                endpoint = _status.value.endpoint.takeIf { choice == _status.value.choice },
                distributors = distributors.map { PushDistributor(it, environment.distributorLabel(it)) },
            )
            choice
        }
        when (choice) {
            PushChoice.Fcm -> environment.fcmToken()?.let { onNewEndpoint(PushTransport.FCM, it) }
            is PushChoice.UnifiedPush -> {
                store.data.first()[ENDPOINT]?.let { onNewEndpoint(PushTransport.UNIFIED_PUSH, it) }
                environment.registerUnifiedPush(choice.distributor)
            }
            PushChoice.None -> forgetOnServer()
        }
    }

    /**
     * A new endpoint from [transport]: an FCM token (also a rotated one, from
     * `onNewToken`) or a UnifiedPush URL. Kept, and sent to the server when
     * signed in and it is the transport in use.
     */
    suspend fun onNewEndpoint(transport: String, endpoint: String) {
        mutex.withLock {
            if (store.data.first()[TRANSPORT] != transport) return
            store.edit { it[ENDPOINT] = endpoint }
            if (signedIn.first() != true || registered == (transport to endpoint)) return
            val id = client.registerPushEndpoint(transport, endpoint)
            if (id == null) {
                Log.w(TAG, "The server did not take the $transport endpoint; the periodic sync carries on")
                return
            }
            store.edit { it[ENDPOINT_ID] = id }
            registered = transport to endpoint
            _status.value = _status.value.copy(registered = true, endpoint = endpoint)
        }
    }

    /** The distributor dropped this app (uninstalled, or the user removed it there). */
    suspend fun onUnregistered() {
        mutex.withLock { store.edit { it.remove(ENDPOINT) } }
        refresh()
    }

    /** Settings: use [packageName] among several distributors. */
    suspend fun chooseDistributor(packageName: String) {
        store.edit { it[CHOSEN_DISTRIBUTOR] = packageName }
        refresh()
    }

    /** Before signing out, with the token still valid: the server forgets the endpoint, the distributor this app. */
    suspend fun onSignOut(serverUrl: String, token: String) {
        if (!started) return
        mutex.withLock {
            val prefs = store.data.first()
            prefs[ENDPOINT_ID]?.let { client.deletePushEndpoint(serverUrl, token, it) }
            if (prefs[TRANSPORT] == PushTransport.UNIFIED_PUSH) environment.unregisterUnifiedPush()
            store.edit {
                it.remove(ENDPOINT_ID)
                it.remove(ENDPOINT)
            }
            registered = null
            _status.value = _status.value.copy(registered = false, endpoint = null)
        }
    }

    /** No transport any more: the server stops sending to the old endpoint. */
    private suspend fun forgetOnServer() {
        mutex.withLock {
            store.data.first()[ENDPOINT_ID]?.let { client.deletePushEndpoint(it) }
            store.edit {
                it.remove(ENDPOINT)
                it.remove(ENDPOINT_ID)
            }
            registered = null
            _status.value = _status.value.copy(registered = false)
        }
    }

    private val PushChoice.transport: String?
        get() = when (this) {
            PushChoice.Fcm -> PushTransport.FCM
            is PushChoice.UnifiedPush -> PushTransport.UNIFIED_PUSH
            PushChoice.None -> null
        }

    private companion object {
        const val TAG = "TungPush"
        val TRANSPORT = stringPreferencesKey("push_transport")
        val ENDPOINT = stringPreferencesKey("push_endpoint")
        val ENDPOINT_ID = stringPreferencesKey("push_endpoint_id")
        val CHOSEN_DISTRIBUTOR = stringPreferencesKey("push_distributor")
    }
}
