package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.core.CoreCatalog
import io.github.kanylbullen.tung.core.sync.SyncFailure
import io.github.kanylbullen.tung.web.i18n.Strings
import io.github.kanylbullen.tung.web.store.IndexedDbStore
import io.github.kanylbullen.tung.web.store.TungStore
import io.github.kanylbullen.tung.web.store.Write
import io.github.kanylbullen.tung.web.sync.AuthResult
import io.github.kanylbullen.tung.web.sync.WebAuth
import io.github.kanylbullen.tung.web.sync.WebSettings
import io.github.kanylbullen.tung.web.sync.WebSync
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The server a fresh browser signs in to, the phone's
 * `BuildConfig.DEFAULT_SERVER_URL`: one line for a self-hoster's fork.
 */
const val DEFAULT_SERVER_URL = "https://tung.xuper.fun"

/**
 * The web's `TungContainer`: the store, IndexedDB, the settings, sign-in and
 * sync, built once by `main` and handed to the screens. [start] opens the
 * database and reads everything into memory; until then [phase] is
 * [Phase.Loading].
 */
class WebContainer(
    val strings: Strings,
    /** The browser's locale tag, for dates and numbers. */
    val locale: String,
    private val scope: CoroutineScope,
) {
    sealed interface Phase {
        data object Loading : Phase
        data object Ready : Phase
        data class Failed(val message: String) : Phase
    }

    val store = TungStore()
    val auth = WebAuth()

    private val _phase = MutableStateFlow<Phase>(Phase.Loading)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    lateinit var db: IndexedDbStore
        private set
    lateinit var settings: WebSettings
        private set
    lateinit var sync: WebSync
        private set

    /** The core catalogue's names by key and language, for the exercise names on screen. */
    var catalogueNames: Map<String, Map<String, String>> = emptyMap()
        private set

    /** Opens IndexedDB, loads the log, seeds the catalogue and, when signed in, starts a sync. */
    suspend fun start() {
        try {
            db = IndexedDbStore.open()
            settings = WebSettings(db).also { it.load() }
            db.loadInto(store)
            val catalogue = CoreCatalog.parse(window.fetch("catalog/core.json").await().text().await())
            catalogueNames = catalogue.associate { it.key to it.name }
            val journal = ArrayList<Write>()
            store.seedCatalogue(catalogue, journal)
            db.write(journal)
            store.bump()
            sync = WebSync(store, db, settings)
            _phase.value = Phase.Ready
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            console.error("Tung could not start", e)
            _phase.value = Phase.Failed(e.message ?: e.toString())
            return
        }
        if (settings.account.value != null) syncInBackground()
        // The phone syncs when it comes to the foreground; the browser when
        // the page is shown again or the connection comes back.
        window.addEventListener("online", { syncInBackground() })
        window.addEventListener("visibilitychange", { if (window.document.asDynamic().visibilityState == "visible") syncInBackground() })
    }

    /** Signs in and, on success, pulls the account's log. */
    suspend fun signIn(serverUrl: String, email: String, password: String): AuthResult {
        val result = auth.signIn(serverUrl, email, password)
        if (result is AuthResult.Success) {
            settings.signedIn(serverUrl, result.email, result.token)
            val journal = ArrayList<Write>()
            store.markEverythingDirty(journal)
            db.write(journal)
            syncInBackground()
        }
        return result
    }

    /** Forgets the token here and on the server; the log stays in this browser, as on the phone. */
    suspend fun signOut() {
        val account = settings.account.value
        val token = settings.token
        settings.signedOut(failure = null)
        if (account != null && token != null) auth.signOut(account.serverUrl, token)
    }

    /** A sync that nobody waits for; its outcome shows on the status line. */
    fun syncInBackground() {
        if (settings.account.value == null) return
        scope.launch { sync.syncOnce() }
    }

    /** The sign-in screen's hint after a 401 signed the browser out. */
    val signedOutByServer: Boolean
        get() = settings.syncState.value.lastError == SyncFailure.SIGNED_OUT.key && settings.account.value == null
}
