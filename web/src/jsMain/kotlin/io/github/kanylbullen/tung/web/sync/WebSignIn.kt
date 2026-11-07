package io.github.kanylbullen.tung.web.sync

import io.github.kanylbullen.tung.core.sync.DataOwner
import io.github.kanylbullen.tung.core.sync.LocalDataOnSignIn
import io.github.kanylbullen.tung.core.sync.localDataOnSignIn
import io.github.kanylbullen.tung.web.store.IndexedDbStore
import io.github.kanylbullen.tung.web.store.TungStore
import io.github.kanylbullen.tung.web.store.Write
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A sign-in held back until [WebSignIn.resolve]: who signed in, where. */
data class PendingSignIn(val serverUrl: String, val email: String)

/** What happens to another account's data in this browser when a new account signs in. */
enum class OtherAccountChoice {
    /** "Delete them": the log in this browser goes ([TungStore.wipe]), then the new account syncs down. */
    DELETE,

    /** "Add them to <email>": everything here is uploaded to the new account, as before issue #152. */
    ADD,
}

/**
 * What a sign-in the server accepted does with the log already in this
 * browser (issue #152), the phone's `SyncAccount.storeSession`: the same
 * account again, a first sign-in or nothing to upload stores the session
 * and marks everything dirty, as before; another account's data holds the
 * session in memory ([pending]) until the user answers with [resolve].
 */
class WebSignIn(
    private val store: TungStore,
    private val db: IndexedDbStore,
    private val settings: WebSettings,
    /** The server forgets a held token when the question is dismissed. */
    private val revoke: suspend (serverUrl: String, token: String) -> Unit,
) {
    private class Held(val serverUrl: String, val session: AuthResult.Success)

    private var held: Held? = null
    private val _pending = MutableStateFlow<PendingSignIn?>(null)

    /** The question "This device has workouts from another account", while it is open. */
    val pending: StateFlow<PendingSignIn?> = _pending.asStateFlow()

    /** True when signed in now, false when the session waits for [resolve]. */
    suspend fun accept(serverUrl: String, session: AuthResult.Success): Boolean {
        val rule = localDataOnSignIn(
            recorded = settings.owner,
            incoming = session.userId?.let { DataOwner(serverUrl, it) },
            hasLocalData = store.hasUserData(),
            previousServerUrl = settings.lastServerUrl,
            previousEmail = settings.lastEmail,
            incomingEmail = session.email,
        )
        if (rule == LocalDataOnSignIn.UPLOAD) {
            complete(serverUrl, session, wipe = false)
            return true
        }
        held?.let { revoke(it.serverUrl, it.session.token) }
        held = Held(serverUrl, session)
        _pending.value = PendingSignIn(serverUrl, session.email)
        return false
    }

    /**
     * The answer: [OtherAccountChoice.DELETE] wipes the log here and signs
     * in, [OtherAccountChoice.ADD] signs in as before issue #152, null (the
     * question dismissed) revokes the held token and changes nothing. True
     * when signed in now.
     */
    suspend fun resolve(choice: OtherAccountChoice?): Boolean {
        val answered = held ?: return false
        held = null
        _pending.value = null
        if (choice == null) {
            revoke(answered.serverUrl, answered.session.token)
            return false
        }
        complete(answered.serverUrl, answered.session, wipe = choice == OtherAccountChoice.DELETE)
        return true
    }

    private suspend fun complete(serverUrl: String, session: AuthResult.Success, wipe: Boolean) {
        val journal = ArrayList<Write>()
        if (wipe) store.wipe(journal) else store.markEverythingDirty(journal)
        db.write(journal)
        store.bump()
        settings.signedIn(serverUrl, session.email, session.token)
        session.userId?.let { settings.setOwner(DataOwner(serverUrl, it)) }
    }
}
