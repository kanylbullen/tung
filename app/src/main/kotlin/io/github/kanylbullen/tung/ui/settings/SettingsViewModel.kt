package io.github.kanylbullen.tung.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.auth.GoogleSignInResult
import io.github.kanylbullen.tung.core.sync.ServerUrlCheck
import io.github.kanylbullen.tung.core.sync.checkServerUrl
import io.github.kanylbullen.tung.push.PushRegistrar
import io.github.kanylbullen.tung.push.PushStatus
import io.github.kanylbullen.tung.sync.AccountState
import io.github.kanylbullen.tung.sync.AuthFailure
import io.github.kanylbullen.tung.sync.AuthMethodsResult
import io.github.kanylbullen.tung.sync.AuthResult
import io.github.kanylbullen.tung.sync.LinkCode
import io.github.kanylbullen.tung.sync.LinkFailure
import io.github.kanylbullen.tung.sync.LinkStartResult
import io.github.kanylbullen.tung.sync.SyncAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The sign-in form as typed. [error] is a string resource, shown under the buttons. */
data class SignInForm(
    val serverUrl: String = "",
    val email: String = "",
    val password: String = "",
    val busy: Boolean = false,
    @StringRes val error: Int? = null,
    /**
     * The Google client id the server at [googleServer] named in
     * `/auth/methods`; null hides "Sign in with Google" (not offered, not
     * asked yet, or the address changed since).
     */
    val googleClientId: String? = null,
    val googleServer: String? = null,
)

/**
 * "Link a watch": the code from the server while it is shown, and why there
 * is none. [error] is a string resource.
 */
data class WatchLinkState(
    val code: LinkCode? = null,
    val busy: Boolean = false,
    @StringRes val error: Int? = null,
)

data class SettingsUiState(
    /** Null until the account has been read. */
    val account: AccountState? = null,
    val form: SignInForm = SignInForm(),
    val watchLink: WatchLinkState = WatchLinkState(),
    /** How the server wakes this phone: FCM, a UnifiedPush distributor, or nothing. */
    val push: PushStatus = PushStatus(),
)

/**
 * Settings: the server and the account. Sign-in, sign-out and "Sync now" go
 * through [SyncAccount]; the screen never sees the token.
 */
class SettingsViewModel(private val account: SyncAccount, private val push: PushRegistrar) : ViewModel() {

    private val form = MutableStateFlow(SignInForm())
    private val watchLink = MutableStateFlow(WatchLinkState())

    val state: StateFlow<SettingsUiState> = combine(account.state, form, watchLink, push.status) { a, f, w, p -> SettingsUiState(a, f, w, p) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private var methodsJob: Job? = null

    init {
        // A distributor installed since the app started shows up here, and gets used.
        viewModelScope.launch { if (account.isSignedIn()) push.refresh() }
        // "Google linked" follows the server, also after a link made on the consent page.
        viewModelScope.launch { account.refreshAccount() }
        // The last server and email, so signing in again is one password away.
        viewModelScope.launch {
            val stored = account.state.first()
            form.update {
                it.copy(
                    serverUrl = it.serverUrl.ifEmpty { stored.serverUrl.orEmpty() },
                    email = it.email.ifEmpty { stored.email.orEmpty() },
                )
            }
            // Whenever the form shows (here, and after signing out), ask its server whether it offers Google.
            account.state.map { it.signedIn }.distinctUntilChanged().collect { signedIn ->
                if (signedIn == false) lookUpMethods(form.value.serverUrl, waitMillis = 0)
            }
        }
    }

    fun onServerUrl(value: String) {
        form.update { it.copy(serverUrl = value, error = null, googleClientId = null, googleServer = null) }
        lookUpMethods(value)
    }

    /**
     * Asks the server at [raw] (once it is an address `checkServerUrl`
     * accepts, and the typing has paused) whether it offers Google sign-in.
     * On Dispatchers.Default, so the pause is real time and not the main
     * looper's. A stale answer, for an address no longer in the field, is dropped.
     */
    private fun lookUpMethods(raw: String, waitMillis: Long = 600) {
        methodsJob?.cancel()
        val url = (checkServerUrl(raw) as? ServerUrlCheck.Valid)?.url ?: return
        methodsJob = viewModelScope.launch(Dispatchers.Default) {
            delay(waitMillis)
            val clientId = (account.authMethods(url) as? AuthMethodsResult.Success)?.methods?.googleClientId ?: return@launch
            form.update {
                if ((checkServerUrl(it.serverUrl) as? ServerUrlCheck.Valid)?.url == url) it.copy(googleClientId = clientId, googleServer = url) else it
            }
        }
    }

    /**
     * "Sign in with Google": [obtain] asks Google (Credential Manager, over the
     * screen's activity) for an ID token for the server's client id, which
     * goes to the server like a password would. Closing Google's sheet is not
     * an error.
     */
    fun signInWithGoogle(obtain: suspend (clientId: String) -> GoogleSignInResult) {
        val current = form.value
        if (current.busy) return
        val clientId = current.googleClientId ?: return
        val url = current.googleServer ?: return
        form.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val error = when (val got = obtain(clientId)) {
                is GoogleSignInResult.Token -> when (val result = account.signInWithGoogle(url, got.idToken)) {
                    is AuthResult.Success -> null
                    is AuthResult.Failed -> message(result.failure)
                }
                GoogleSignInResult.Cancelled -> null
                GoogleSignInResult.NoAccount -> R.string.sync_error_google_no_account
                GoogleSignInResult.Unavailable -> R.string.sync_error_google_unavailable
                is GoogleSignInResult.Failed -> R.string.sync_error_google_failed
            }
            form.update { it.copy(busy = false, error = error, password = if (error == null) "" else it.password) }
        }
    }

    fun onEmail(value: String) = form.update { it.copy(email = value, error = null) }

    fun onPassword(value: String) = form.update { it.copy(password = value, error = null) }

    /** Sign in, or create an account when [create]. Checks the address and the fields before asking the server. */
    fun signIn(create: Boolean) {
        val current = form.value
        if (current.busy) return
        val url = when (val check = checkServerUrl(current.serverUrl)) {
            is ServerUrlCheck.Valid -> check.url
            ServerUrlCheck.Malformed -> return form.update { it.copy(error = R.string.sync_error_url) }
            ServerUrlCheck.NeedsHttps -> return form.update { it.copy(error = R.string.sync_error_url_https) }
        }
        if (current.email.isBlank() || current.password.isEmpty()) {
            return form.update { it.copy(error = R.string.sync_error_fields) }
        }
        form.update { it.copy(serverUrl = url, busy = true, error = null) }
        viewModelScope.launch {
            when (val result = account.signIn(url, current.email, current.password, create)) {
                is AuthResult.Success -> form.update { it.copy(password = "", busy = false) }
                is AuthResult.Failed -> form.update { it.copy(busy = false, error = message(result.failure)) }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { account.signOut() }
    }

    /** "Choose UnifiedPush distributor", when several are installed. */
    fun chooseDistributor(packageName: String) {
        viewModelScope.launch { push.chooseDistributor(packageName) }
    }

    fun syncNow() {
        viewModelScope.launch { account.syncNow() }
    }

    /**
     * Asks the server for a link code (`POST /api/v1/link/start`) that the
     * watch trades for its own token; the screen shows it until it expires.
     */
    fun linkWatch() {
        if (watchLink.value.busy) return
        watchLink.value = WatchLinkState(busy = true)
        viewModelScope.launch {
            watchLink.value = when (val result = account.startLink()) {
                is LinkStartResult.Success -> WatchLinkState(code = result.code)
                is LinkStartResult.Failed -> WatchLinkState(error = linkMessage(result.failure))
            }
        }
    }

    /** Hides the code (the watch is linked, or the user changed their mind). */
    fun closeWatchLink() {
        watchLink.value = WatchLinkState()
    }

    @StringRes
    private fun linkMessage(failure: LinkFailure): Int = when (failure) {
        LinkFailure.NETWORK -> R.string.sync_error_unreachable
        LinkFailure.RATE_LIMITED -> R.string.sync_error_rate_limited
        LinkFailure.NOT_A_SERVER -> R.string.link_watch_error_old_server
        LinkFailure.SIGNED_OUT, LinkFailure.NOT_SIGNED_IN -> R.string.sync_error_signed_out
        LinkFailure.INVALID_CODE, LinkFailure.SERVER -> R.string.sync_error_server_auth
    }

    @StringRes
    private fun message(failure: AuthFailure): Int = when (failure) {
        AuthFailure.INVALID_CREDENTIALS -> R.string.sync_error_credentials
        AuthFailure.ACCOUNT_EXISTS -> R.string.sync_error_exists
        AuthFailure.WEAK_PASSWORD -> R.string.sync_error_weak_password
        AuthFailure.INVALID_REQUEST -> R.string.sync_error_invalid
        AuthFailure.RATE_LIMITED -> R.string.sync_error_rate_limited
        AuthFailure.NOT_A_SERVER -> R.string.sync_error_not_a_server
        AuthFailure.NETWORK -> R.string.sync_error_unreachable
        AuthFailure.SERVER -> R.string.sync_error_server_auth
        AuthFailure.GOOGLE_REJECTED -> R.string.sync_error_google_rejected
        AuthFailure.GOOGLE_EMAIL_UNVERIFIED -> R.string.sync_error_google_unverified
        AuthFailure.GOOGLE_ACCOUNT_MISMATCH -> R.string.sync_error_google_mismatch
        AuthFailure.GOOGLE_NOT_OFFERED -> R.string.sync_error_google_not_offered
    }
}
