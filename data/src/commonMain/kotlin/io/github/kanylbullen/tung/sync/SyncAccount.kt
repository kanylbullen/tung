package io.github.kanylbullen.tung.sync

import io.github.kanylbullen.tung.core.sync.SyncResult
import io.github.kanylbullen.tung.core.sync.SyncFailure
import io.github.kanylbullen.tung.core.sync.SyncState
import io.github.kanylbullen.tung.data.ioDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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
)

/**
 * The account on a server and when to sync: sign in or create an account,
 * sign out, sync now, and the background triggers ([requestSync] after a
 * finished workout and when the app comes to the foreground, the periodic
 * work while signed in). Local data is never touched by signing in or out.
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
) {
    private val signedIn = MutableStateFlow<Boolean?>(null)

    val state: Flow<AccountState> = combine(
        combine(settings.serverUrl, settings.email, settings.googleLinked, ::Triple),
        settings.state,
        signedIn.onStart { isSignedIn() },
        client.running,
    ) { (url, email, google), sync, signedIn, running -> AccountState(url, email, signedIn, sync, running, google) }

    suspend fun isSignedIn(): Boolean =
        signedIn.value ?: withContext(ioDispatcher) { tokens.read() != null }.also { signedIn.value = it }

    /**
     * Logs in (or registers, when [create]) at [serverUrl], an address
     * `core.sync.checkServerUrl` accepted. On success the token is stored,
     * the cursor starts over at 0 and every local row is marked dirty, so a
     * new account or server receives everything this phone has; then the
     * periodic sync is scheduled and a first one enqueued.
     */
    suspend fun signIn(serverUrl: String, email: String, password: String, create: Boolean): AuthResult {
        val result = client.authenticate(serverUrl, email.trim(), password, create)
        if (result is AuthResult.Success) storeSession(serverUrl, result.token, result.email, result.googleLinked)
        return result
    }

    /** What [serverUrl] offers for signing in (the Google button shows only when it names a client id). */
    suspend fun authMethods(serverUrl: String): AuthMethodsResult = client.authMethods(serverUrl)

    /**
     * Signs in with a Google ID token (from Credential Manager, for the
     * client id [authMethods] named) at [serverUrl], an address
     * `core.sync.checkServerUrl` accepted. From there on it is the same as
     * [signIn]: token stored, cursor 0, everything dirty, work scheduled.
     */
    suspend fun signInWithGoogle(serverUrl: String, idToken: String): AuthResult {
        val result = client.loginWithGoogle(serverUrl, idToken)
        if (result is AuthResult.Success) storeSession(serverUrl, result.token, result.email, result.googleLinked)
        return result
    }

    /** Asks the server (`/me`) whether a Google account is linked, so Settings shows it after a link made elsewhere. */
    suspend fun refreshAccount() {
        if (!isSignedIn()) return
        client.me()?.let { settings.setGoogleLinked(it.googleLinked) }
    }

    private suspend fun storeSession(serverUrl: String, token: String, email: String, googleLinked: Boolean = false) {
        withContext(ioDispatcher) {
            tokens.write(token)
            settings.signedIn(serverUrl, email, googleLinked)
            store.markEverythingDirty()
        }
        signedIn.value = true
        scheduler.schedulePeriodic()
        scheduler.enqueueNow()
    }

    /**
     * Signs in with a link code from another device (the watch's way in, no
     * password): [code] is traded for a token at [serverUrl], an address
     * `core.sync.checkServerUrl` accepted, and from there on it is the same
     * as [signIn].
     */
    suspend fun signInWithCode(serverUrl: String, code: String): LinkClaimResult {
        val result = client.claimLink(serverUrl, code)
        if (result is LinkClaimResult.Success) storeSession(serverUrl, result.token, result.email)
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
