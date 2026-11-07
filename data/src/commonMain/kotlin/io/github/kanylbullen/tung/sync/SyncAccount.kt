package io.github.kanylbullen.tung.sync

import io.github.kanylbullen.tung.core.sync.DataOwner
import io.github.kanylbullen.tung.core.sync.LocalDataOnSignIn
import io.github.kanylbullen.tung.core.sync.SyncResult
import io.github.kanylbullen.tung.core.sync.localDataOnSignIn
import io.github.kanylbullen.tung.core.sync.SyncFailure
import io.github.kanylbullen.tung.core.sync.SyncState
import io.github.kanylbullen.tung.data.ioDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the settings screen shows. [signedIn] is null until the token store has been read. */
data class AccountState(
    val serverUrl: String?,
    val email: String?,
    val signedIn: Boolean?,
    val sync: SyncState,
    val syncing: Boolean,
    /** A Google account is linked to this account (shown as "Google linked"). */
    val googleLinked: Boolean = false,
    /** An Apple ID is linked to this account (shown as "Apple linked"). */
    val appleLinked: Boolean = false,
    /**
     * A sign-in the server accepted that waits for the user's choice, because
     * this device holds another account's data (issue #152); null otherwise.
     */
    val otherAccountData: PendingSignIn? = null,
)

/** A sign-in held back until [SyncAccount.resolveOtherAccountData]: who signed in, where. */
data class PendingSignIn(val serverUrl: String, val email: String)

/** What happens to another account's data on this device when a new account signs in. */
enum class OtherAccountChoice {
    /** "Delete them": the local training data goes ([SyncStore.wipe]), then the new account syncs down. */
    DELETE,

    /** "Add them to <email>": everything local is uploaded to the new account, as before issue #152. */
    ADD,
}

/**
 * The account on a server and when to sync: sign in or create an account,
 * sign out, sync now, and the background triggers ([requestSync] after a
 * finished workout and when the app comes to the foreground, the periodic
 * work while signed in). Signing out keeps local data; signing in as
 * another account than the one it belongs to asks first (issue #152).
 */
class SyncAccount(
    private val client: SyncClient,
    private val settings: SyncSettings,
    private val tokens: TokenStore,
    private val store: SyncStore,
    private val scheduler: SyncScheduler,
    private val scope: CoroutineScope,
    /** Runs with the server and token just before signing out, while the token still works (the push endpoint's removal). */
    private val beforeSignOut: suspend (serverUrl: String, token: String) -> Unit = { _, _ -> },
    /** Runs right after another account's data was wiped: what else on the device was that account's (the last import). */
    private val afterWipe: suspend () -> Unit = {},
) {
    private val signedIn = MutableStateFlow<Boolean?>(null)

    /** A session the server gave, not stored yet: the user is asked what happens to another account's data. */
    private class Pending(val serverUrl: String, val session: AuthResult.Success)

    private val pending = MutableStateFlow<Pending?>(null)

    val state: Flow<AccountState> = combine(
        combine(settings.serverUrl, settings.email, settings.googleLinked, settings.appleLinked) { url, email, google, apple ->
            Identity(url, email, google, apple)
        },
        settings.state,
        signedIn.onStart { isSignedIn() },
        client.running,
        pending,
    ) { id, sync, signedIn, running, held ->
        AccountState(
            id.url, id.email, signedIn, sync, running, id.googleLinked, id.appleLinked,
            held?.let { PendingSignIn(it.serverUrl, it.session.email) },
        )
    }

    private data class Identity(val url: String?, val email: String?, val googleLinked: Boolean, val appleLinked: Boolean)

    suspend fun isSignedIn(): Boolean =
        signedIn.value ?: withContext(ioDispatcher) { tokens.read() != null }.also { signedIn.value = it }

    /**
     * Logs in (or registers, when [create]) at [serverUrl], an address
     * `core.sync.checkServerUrl` accepted. On success the token is stored,
     * the cursor starts over at 0 and every local row is marked dirty, so a
     * new account or server receives everything this phone has; then the
     * periodic sync is scheduled and a first one enqueued.
     *
     * Unless the device holds another account's data (issue #152,
     * `core.sync.localDataOnSignIn`): then nothing is stored, marked or
     * synced yet, the result is still [AuthResult.Success], and
     * [AccountState.otherAccountData] asks the user, who answers with
     * [resolveOtherAccountData].
     */
    suspend fun signIn(serverUrl: String, email: String, password: String, create: Boolean): AuthResult {
        val result = client.authenticate(serverUrl, email.trim(), password, create)
        if (result is AuthResult.Success) storeSession(serverUrl, result)
        return result
    }

    /** "Forgot password?": a reset link mailed to [email] at [serverUrl]. */
    suspend fun forgotPassword(serverUrl: String, email: String): ForgotOutcome = client.forgotPassword(serverUrl, email.trim())

    /** What [serverUrl] offers for signing in (the Google and Apple buttons show only when it names a client id). */
    suspend fun authMethods(serverUrl: String): AuthMethodsResult = client.authMethods(serverUrl)

    /**
     * Signs in with a Google ID token (from Credential Manager, for the
     * client id [authMethods] named) at [serverUrl], an address
     * `core.sync.checkServerUrl` accepted. From there on it is the same as
     * [signIn]: token stored, cursor 0, everything dirty, work scheduled.
     */
    suspend fun signInWithGoogle(serverUrl: String, idToken: String): AuthResult {
        val result = client.loginWithGoogle(serverUrl, idToken)
        if (result is AuthResult.Success) storeSession(serverUrl, result)
        return result
    }

    /**
     * Signs in with Sign in with Apple's ID token (from AuthenticationServices
     * on an iPhone) at [serverUrl], as [signInWithGoogle] does with Google's.
     */
    suspend fun signInWithApple(serverUrl: String, idToken: String): AuthResult {
        val result = client.loginWithApple(serverUrl, idToken)
        if (result is AuthResult.Success) storeSession(serverUrl, result)
        return result
    }

    /** A new link to confirm the account's email (see [SyncFailure.EMAIL_NOT_VERIFIED]). */
    suspend fun resendConfirmation(): ResendOutcome = client.resendConfirmation()

    /** Asks the server (`/me`) whether a Google account or an Apple ID is linked, so Settings shows it after a link made elsewhere. */
    suspend fun refreshAccount() {
        if (!isSignedIn()) return
        val me = client.me() ?: return
        settings.setLinked(google = me.googleLinked, apple = me.appleLinked)
        // A device signed in before owners were recorded learns whose data it holds.
        val url = settings.serverUrl()
        if (me.userId != null && url != null && settings.owner() == null) settings.setOwner(DataOwner(url, me.userId))
    }

    /**
     * The server accepted [session]: stored at once, or held for the user's
     * choice when the device holds another account's data. With [ask] false
     * (the watch, where a dialog is hard on a wrist) that data is wiped
     * instead of asked about.
     */
    private suspend fun storeSession(serverUrl: String, session: AuthResult.Success, ask: Boolean = true) {
        val rule = withContext(ioDispatcher) {
            localDataOnSignIn(
                recorded = settings.owner(),
                incoming = session.userId?.let { DataOwner(serverUrl, it) },
                hasLocalData = store.hasLocalData(),
                previousServerUrl = settings.serverUrl(),
                previousEmail = settings.email(),
                incomingEmail = session.email,
            )
        }
        when {
            rule == LocalDataOnSignIn.UPLOAD -> completeSession(serverUrl, session, wipe = false)
            !ask -> completeSession(serverUrl, session, wipe = true)
            else -> pending.getAndUpdate { Pending(serverUrl, session) }?.let { client.logout(it.serverUrl, it.session.token) }
        }
    }

    /**
     * The user's answer to [AccountState.otherAccountData]: [OtherAccountChoice.DELETE]
     * wipes the local training data and signs in, [OtherAccountChoice.ADD]
     * signs in as before issue #152 (everything is uploaded), and null (the
     * dialog dismissed) signs out again: the held token is revoked and
     * nothing on the device changes.
     */
    suspend fun resolveOtherAccountData(choice: OtherAccountChoice?) {
        val held = pending.getAndUpdate { null } ?: return
        when (choice) {
            OtherAccountChoice.DELETE -> completeSession(held.serverUrl, held.session, wipe = true)
            OtherAccountChoice.ADD -> completeSession(held.serverUrl, held.session, wipe = false)
            null -> client.logout(held.serverUrl, held.session.token)
        }
    }

    private suspend fun completeSession(serverUrl: String, session: AuthResult.Success, wipe: Boolean) {
        withContext(ioDispatcher) {
            if (wipe) {
                store.wipe()
                afterWipe()
            }
            tokens.write(session.token)
            settings.signedIn(serverUrl, session.email, session.googleLinked, session.appleLinked)
            session.userId?.let { settings.setOwner(DataOwner(serverUrl, it)) }
            if (!wipe) store.markEverythingDirty()
        }
        signedIn.value = true
        scheduler.schedulePeriodic()
        scheduler.enqueueNow()
    }

    /**
     * Signs in with a link code from another device (the watch's way in, no
     * password): [code] is traded for a token at [serverUrl], an address
     * `core.sync.checkServerUrl` accepted, and from there on it is the same
     * as [signIn], except that another account's data is wiped without
     * asking (issue #152): a watch has no room for the question, and its log
     * is the account's once it syncs.
     */
    suspend fun signInWithCode(serverUrl: String, code: String): LinkClaimResult {
        val result = client.claimLink(serverUrl, code)
        if (result is LinkClaimResult.Success) {
            storeSession(serverUrl, AuthResult.Success(result.token, result.email, userId = result.userId), ask = false)
        }
        return result
    }

    /**
     * Feedback from this device's account ([SyncClient.sendFeedback]); a 401
     * signs this device out, as during sync.
     */
    suspend fun sendFeedback(kind: String, message: String, images: List<ByteArray>, platform: String, appVersion: String?): FeedbackOutcome {
        val result = client.sendFeedback(kind, message, images, platform, appVersion)
        if (result == FeedbackOutcome.SIGNED_OUT) {
            withContext(ioDispatcher) { tokens.write(null) }
            settings.signedOut(SyncFailure.SIGNED_OUT)
            signedOutByServer()
        }
        return result
    }

    /** A code for another device to sign in with ([signInWithCode] there); this device must be signed in. */
    suspend fun startLink(): LinkStartResult {
        val result = client.startLink()
        if (result is LinkStartResult.Failed && result.failure == LinkFailure.SIGNED_OUT) {
            // Same as a 401 during sync: the token is dead, so this device is signed out.
            withContext(ioDispatcher) { tokens.write(null) }
            settings.signedOut(SyncFailure.SIGNED_OUT)
            signedOutByServer()
        }
        return result
    }

    /** Cancels the background sync, forgets the token, then asks the server to revoke it. Local data stays. */
    suspend fun signOut() {
        scheduler.cancel()
        withContext(ioDispatcher) { settings.serverUrl() to tokens.read() }.let { (url, token) ->
            if (url != null && token != null) beforeSignOut(url, token)
        }
        val (url, token) = withContext(ioDispatcher) {
            val token = tokens.read()
            tokens.write(null)
            settings.serverUrl() to token
        }
        signedIn.value = false
        settings.signedOut(failure = null)
        if (url != null && token != null) client.logout(url, token)
    }

    /**
     * Deletes the account on the server ([SyncClient.deleteAccount], with
     * [email] typed again), then signs this device out as [signOut] does,
     * without the server calls: the token and the push endpoint went with
     * the account. A 401 signs out as during sync. Local data stays.
     */
    suspend fun deleteAccount(email: String): DeleteAccountOutcome {
        val (url, token) = withContext(ioDispatcher) { settings.serverUrl() to tokens.read() }
        val result = client.deleteAccount(email)
        when (result) {
            DeleteAccountOutcome.DELETED -> {
                scheduler.cancel()
                // The endpoint went with the account; this forgets it here (and UnifiedPush's registration).
                if (url != null && token != null) beforeSignOut(url, token)
                withContext(ioDispatcher) { tokens.write(null) }
                signedIn.value = false
                settings.signedOut(failure = null)
            }
            DeleteAccountOutcome.SIGNED_OUT -> {
                withContext(ioDispatcher) { tokens.write(null) }
                settings.signedOut(SyncFailure.SIGNED_OUT)
                signedOutByServer()
            }
            else -> Unit
        }
        return result
    }

    /** The server answered 401 during a sync; [SyncClient] has already cleared the token. */
    fun signedOutByServer() {
        signedIn.value = false
        scheduler.cancel()
    }

    /** "Sync now": right here, not through WorkManager, so the screen sees the result at once. */
    suspend fun syncNow(): SyncResult = client.syncOnce()

    /** A sync soon, if signed in: after a finished workout and when the app comes to the foreground. */
    fun requestSync() {
        scope.launch { if (isSignedIn()) scheduler.enqueueNow() }
    }

    /** At app start: keeps the periodic sync scheduled while signed in (it survives reboots anyway). */
    fun restoreSchedule() {
        scope.launch { if (isSignedIn()) scheduler.schedulePeriodic() }
    }
}
