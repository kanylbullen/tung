package io.github.kanylbullen.tung.ui.settings

import io.github.kanylbullen.tung.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.auth.AppleSignInResult
import io.github.kanylbullen.tung.auth.GoogleSignInResult
import io.github.kanylbullen.tung.core.sync.ServerUrlCheck
import io.github.kanylbullen.tung.core.sync.checkServerUrl
import io.github.kanylbullen.tung.sync.AccountState
import io.github.kanylbullen.tung.sync.AuthFailure
import io.github.kanylbullen.tung.sync.AuthMethodsResult
import io.github.kanylbullen.tung.sync.AuthResult
import io.github.kanylbullen.tung.sync.ChangePasswordOutcome
import io.github.kanylbullen.tung.sync.MAX_PASSWORD_LENGTH
import io.github.kanylbullen.tung.sync.MIN_PASSWORD_LENGTH
import io.github.kanylbullen.tung.sync.SignOutOthersOutcome
import io.github.kanylbullen.tung.sync.DeleteAccountOutcome
import io.github.kanylbullen.tung.sync.ForgotOutcome
import io.github.kanylbullen.tung.core.sync.OtherAccountChoice
import io.github.kanylbullen.tung.sync.ResendOutcome
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
    val error: StringResource? = null,
    /** What "Forgot password?" gave, muted under the buttons; [email] is in the text. */
    val notice: StringResource? = null,
    /**
     * The Google client id the server at [googleServer] named in
     * `/auth/methods`; null hides "Sign in with Google" (not offered, not
     * asked yet, or the address changed since).
     */
    val googleClientId: String? = null,
    val googleServer: String? = null,
    /**
     * The server at [appleServer] offers Sign in with Apple (`/auth/methods`
     * names it); false hides the button, which also needs a platform that can
     * ask Apple (an iPhone).
     */
    val appleOffered: Boolean = false,
    val appleServer: String? = null,
    /** The address is a field ("Use another server", or a stored server other than the default); else read-only text. */
    val editingServer: Boolean = false,
)

/** "Delete account…" open: the email typed to confirm, and what the last try gave. */
data class AccountDeletion(
    val email: String = "",
    val busy: Boolean = false,
    val error: StringResource? = null,
)

/** "Sign out other devices…" open: asked once, then the server's answer. */
data class SignOutOthersState(
    val busy: Boolean = false,
    val error: StringResource? = null,
)

/**
 * "Change password…" open: the current password and the new one as typed.
 * [currentError] is said under the current password (a wrong one),
 * [newError] under the new one (too short or long), [error] under both.
 */
data class PasswordChangeState(
    val current: String = "",
    val new: String = "",
    val busy: Boolean = false,
    val currentError: StringResource? = null,
    val newError: StringResource? = null,
    val error: StringResource? = null,
)

/** One line under the account's actions after one of them worked. */
sealed interface AccountNotice {
    /** "Sign out other devices" revoked [count] tokens. */
    data class SignedOutOthers(val count: Int) : AccountNotice

    data object PasswordChanged : AccountNotice
}

/** The account's dialogs and the line their success leaves, as one state. */
private data class AccountActions(
    val signOutOthers: SignOutOthersState? = null,
    val passwordChange: PasswordChangeState? = null,
    val notice: AccountNotice? = null,
)

data class AccountUiState(
    /** Null until the account has been read. */
    val account: AccountState? = null,
    val form: SignInForm = SignInForm(),
    /** The server to use when none is stored: the build's default (`BuildConfig.DEFAULT_SERVER_URL` on Android). */
    val defaultServerUrl: String = "",
    /** The deletion dialog, while it is open. */
    val deletion: AccountDeletion? = null,
    /** The account was just deleted: said above the sign-in form. */
    val deleted: Boolean = false,
    /** What asking for a new confirmation link gave, while it is said; [ResendState.Sending] while it runs. */
    val resend: ResendState? = null,
    /** "Sign out other devices", while its dialog is open. */
    val signOutOthers: SignOutOthersState? = null,
    /** "Change password", while its dialog is open. */
    val passwordChange: PasswordChangeState? = null,
    /** What the last of those two did, said under the buttons. */
    val notice: AccountNotice? = null,
)

/** "Send the link again" under "Confirm your email". */
sealed interface ResendState {
    data object Sending : ResendState
    data class Done(val message: StringResource) : ResendState
}

/**
 * Settings' Account: the server and the account. Sign-in, sign-out and "Sync
 * now" go through [SyncAccount]; the screen never sees the token. The server
 * is the stored one, else [defaultServerUrl] (the public tung-server), shown
 * read-only until "Use another server".
 */
class AccountViewModel(private val account: SyncAccount, private val defaultServerUrl: String) : ViewModel() {

    private val form = MutableStateFlow(SignInForm())
    private val deletion = MutableStateFlow<AccountDeletion?>(null)
    private val deleted = MutableStateFlow(false)
    private val resend = MutableStateFlow<ResendState?>(null)
    private val actions = MutableStateFlow(AccountActions())

    val state: StateFlow<AccountUiState> = combine(
        combine(account.state, form, deletion, deleted, resend) { a, f, d, gone, r -> AccountUiState(a, f, defaultServerUrl, d, gone, r) },
        actions,
    ) { ui, more -> ui.copy(signOutOthers = more.signOutOthers, passwordChange = more.passwordChange, notice = more.notice) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountUiState(defaultServerUrl = defaultServerUrl))

    private var methodsJob: Job? = null

    init {
        // "Google linked" follows the server, also after a link made on the consent page.
        viewModelScope.launch { account.refreshAccount() }
        // The last server and email, so signing in again is one password away; no server yet means the default.
        viewModelScope.launch {
            val stored = account.state.first()
            val server = stored.serverUrl?.takeIf { it.isNotBlank() }
            form.update {
                it.copy(
                    serverUrl = it.serverUrl.ifEmpty { server ?: defaultServerUrl },
                    email = it.email.ifEmpty { stored.email.orEmpty() },
                    // A self-hoster's server shows as the field it was typed into.
                    editingServer = it.editingServer || (server != null && server != defaultServerUrl),
                )
            }
            // Whenever the form shows (here, and after signing out), ask its server whether it offers Google.
            account.state.map { it.signedIn }.distinctUntilChanged().collect { signedIn ->
                if (signedIn == false) {
                    // Signed out some other way (a 401 in the background): the dialog must not come back at the next sign-in.
                    deletion.value = null
                    actions.value = AccountActions()
                    lookUpMethods(form.value.serverUrl, waitMillis = 0)
                }
            }
        }
    }

    /** "Use another server": the address becomes a field, starting from the one shown. */
    fun useAnotherServer() = form.update { it.copy(editingServer = true) }

    fun onServerUrl(value: String) {
        form.update { it.copy(serverUrl = value, error = null, googleClientId = null, googleServer = null, appleOffered = false, appleServer = null) }
        lookUpMethods(value)
    }

    /**
     * Asks the server at [raw] (once it is an address `checkServerUrl`
     * accepts, and the typing has paused) whether it offers Google and Apple sign-in.
     * On Dispatchers.Default, so the pause is real time and not the main
     * looper's. A stale answer, for an address no longer in the field, is dropped.
     */
    private fun lookUpMethods(raw: String, waitMillis: Long = 600) {
        methodsJob?.cancel()
        val url = (checkServerUrl(raw) as? ServerUrlCheck.Valid)?.url ?: return
        methodsJob = viewModelScope.launch(Dispatchers.Default) {
            delay(waitMillis)
            val methods = (account.authMethods(url) as? AuthMethodsResult.Success)?.methods ?: return@launch
            if (methods.googleClientId == null && methods.appleClientId == null) return@launch
            form.update {
                if ((checkServerUrl(it.serverUrl) as? ServerUrlCheck.Valid)?.url != url) return@update it
                it.copy(
                    googleClientId = methods.googleClientId,
                    googleServer = url.takeIf { methods.googleClientId != null },
                    appleOffered = methods.appleClientId != null,
                    appleServer = url.takeIf { methods.appleClientId != null },
                )
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
        deleted.value = false
        viewModelScope.launch {
            val error = when (val got = obtain(clientId)) {
                is GoogleSignInResult.Token -> when (val result = account.signInWithGoogle(url, got.idToken)) {
                    is AuthResult.Success -> null
                    is AuthResult.Failed -> message(result.failure)
                }
                GoogleSignInResult.Cancelled -> null
                GoogleSignInResult.NoAccount -> Res.string.sync_error_google_no_account
                GoogleSignInResult.Unavailable -> Res.string.sync_error_google_unavailable
                is GoogleSignInResult.Failed -> Res.string.sync_error_google_failed
            }
            form.update { it.copy(busy = false, error = error, password = if (error == null) "" else it.password) }
        }
    }

    /**
     * "Continue with Apple": [obtain] asks Apple (AuthenticationServices on an
     * iPhone) for an ID token for this app, which goes to the server like
     * Google's. Closing Apple's sheet is not an error.
     */
    fun signInWithApple(obtain: suspend () -> AppleSignInResult) {
        val current = form.value
        if (current.busy || !current.appleOffered) return
        val url = current.appleServer ?: return
        form.update { it.copy(busy = true, error = null) }
        deleted.value = false
        viewModelScope.launch {
            val error = when (val got = obtain()) {
                is AppleSignInResult.Token -> when (val result = account.signInWithApple(url, got.idToken)) {
                    is AuthResult.Success -> null
                    is AuthResult.Failed -> message(result.failure)
                }
                AppleSignInResult.Cancelled -> null
                is AppleSignInResult.Failed -> Res.string.sync_error_apple_failed
            }
            form.update { it.copy(busy = false, error = error, password = if (error == null) "" else it.password) }
        }
    }

    fun onEmail(value: String) = form.update { it.copy(email = value, error = null, notice = null) }

    /** "Forgot password?": a link to choose a new one, mailed to the address typed above. */
    fun forgotPassword() {
        val current = form.value
        if (current.busy) return
        val url = when (val check = checkServerUrl(current.serverUrl)) {
            is ServerUrlCheck.Valid -> check.url
            ServerUrlCheck.Malformed -> return form.update { it.copy(error = Res.string.sync_error_url) }
            ServerUrlCheck.NeedsHttps -> return form.update { it.copy(error = Res.string.sync_error_url_https) }
        }
        if (current.email.isBlank()) return form.update { it.copy(error = Res.string.sync_forgot_needs_email, notice = null) }
        form.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            val outcome = account.forgotPassword(url, current.email)
            form.update {
                when (outcome) {
                    ForgotOutcome.SENT -> it.copy(busy = false, notice = Res.string.sync_forgot_sent)
                    ForgotOutcome.NOT_OFFERED -> it.copy(busy = false, error = Res.string.sync_forgot_not_offered)
                    ForgotOutcome.RATE_LIMITED -> it.copy(busy = false, error = Res.string.sync_error_rate_limited)
                    ForgotOutcome.INVALID_EMAIL -> it.copy(busy = false, error = Res.string.sync_error_invalid)
                    ForgotOutcome.NETWORK -> it.copy(busy = false, error = Res.string.sync_error_network)
                    ForgotOutcome.FAILED -> it.copy(busy = false, error = Res.string.sync_error_server)
                }
            }
        }
    }

    fun onPassword(value: String) = form.update { it.copy(password = value, error = null) }

    /** Sign in, or create an account when [create]. Checks the address and the fields before asking the server. */
    fun signIn(create: Boolean) {
        val current = form.value
        if (current.busy) return
        val url = when (val check = checkServerUrl(current.serverUrl)) {
            is ServerUrlCheck.Valid -> check.url
            ServerUrlCheck.Malformed -> return form.update { it.copy(error = Res.string.sync_error_url) }
            ServerUrlCheck.NeedsHttps -> return form.update { it.copy(error = Res.string.sync_error_url_https) }
        }
        if (current.email.isBlank() || current.password.isEmpty()) {
            return form.update { it.copy(error = Res.string.sync_error_fields) }
        }
        form.update { it.copy(serverUrl = url, busy = true, error = null) }
        deleted.value = false
        viewModelScope.launch {
            when (val result = account.signIn(url, current.email, current.password, create)) {
                is AuthResult.Success -> form.update { it.copy(password = "", busy = false) }
                is AuthResult.Failed -> form.update { it.copy(busy = false, error = message(result.failure)) }
            }
        }
    }

    /**
     * The answer to "This device has workouts from another account"
     * ([AccountState.otherAccountData]): delete them, add them to the new
     * account, or (null, the dialog dismissed) sign out again with nothing changed.
     */
    fun resolveOtherAccountData(choice: OtherAccountChoice?) {
        viewModelScope.launch { account.resolveOtherAccountData(choice) }
    }

    /** Leaving Account with the question open: the held sign-in is given up and its token revoked. */
    override fun onCleared() {
        account.abandonPendingSignIn()
    }

    fun signOut() {
        viewModelScope.launch { account.signOut() }
    }

    fun syncNow() {
        viewModelScope.launch { account.syncNow() }
    }

    /** "Send the link again": a new link to confirm the email, and one line on how it went. */
    fun resendConfirmation() {
        if (resend.value == ResendState.Sending) return
        resend.value = ResendState.Sending
        viewModelScope.launch {
            resend.value = ResendState.Done(
                when (account.resendConfirmation()) {
                    ResendOutcome.SENT -> Res.string.sync_confirm_sent
                    ResendOutcome.RATE_LIMITED -> Res.string.sync_confirm_rate_limited
                    ResendOutcome.NETWORK, ResendOutcome.FAILED -> Res.string.sync_confirm_failed
                },
            )
        }
    }

    fun openDeletion() = deletion.update { it ?: AccountDeletion() }

    fun onDeletionEmail(value: String) = deletion.update { it?.copy(email = value, error = null) }

    fun cancelDeletion() = deletion.update { if (it?.busy == true) it else null }

    /**
     * Deletes the account on the server with the email typed in the dialog
     * ([SyncAccount.deleteAccount]); on success this device is signed out and
     * the sign-in form says the account is gone. Local data stays.
     */
    fun confirmDeletion() {
        val current = deletion.value ?: return
        if (current.busy) return
        if (current.email.isBlank()) return deletion.update { it?.copy(error = Res.string.sync_delete_error_email) }
        deletion.update { it?.copy(busy = true, error = null) }
        viewModelScope.launch {
            val outcome = account.deleteAccount(current.email)
            val error = when (outcome) {
                DeleteAccountOutcome.DELETED -> null
                DeleteAccountOutcome.EMAIL_MISMATCH -> Res.string.sync_delete_error_email
                DeleteAccountOutcome.NOT_ALLOWED -> Res.string.sync_delete_error_not_allowed
                DeleteAccountOutcome.NOT_A_SERVER -> Res.string.sync_delete_error_old_server
                DeleteAccountOutcome.NETWORK -> Res.string.sync_error_unreachable
                DeleteAccountOutcome.SERVER -> Res.string.sync_error_server_auth
                // Signed out already: the form says so.
                DeleteAccountOutcome.SIGNED_OUT, DeleteAccountOutcome.NOT_SIGNED_IN -> null
            }
            if (error == null) {
                deletion.value = null
                deleted.value = outcome == DeleteAccountOutcome.DELETED
            } else {
                deletion.update { it?.copy(busy = false, error = error) }
            }
        }
    }

    fun openSignOutOthers() = actions.update { it.copy(signOutOthers = it.signOutOthers ?: SignOutOthersState(), notice = null) }

    fun cancelSignOutOthers() = actions.update { if (it.signOutOthers?.busy == true) it else it.copy(signOutOthers = null) }

    /**
     * Every other device of the account is signed out
     * ([SyncAccount.signOutOthers]); this one stays, and nothing local changes.
     */
    fun confirmSignOutOthers() {
        val current = actions.value.signOutOthers ?: return
        if (current.busy) return
        actions.update { it.copy(signOutOthers = SignOutOthersState(busy = true)) }
        viewModelScope.launch {
            val error = when (val outcome = account.signOutOthers()) {
                is SignOutOthersOutcome.Done -> {
                    actions.update { it.copy(signOutOthers = null, notice = AccountNotice.SignedOutOthers(outcome.revoked)) }
                    return@launch
                }
                SignOutOthersOutcome.NotAServer -> Res.string.sync_account_error_old_server
                SignOutOthersOutcome.RateLimited -> Res.string.sync_error_rate_limited
                SignOutOthersOutcome.Network -> Res.string.sync_error_unreachable
                SignOutOthersOutcome.Server -> Res.string.sync_error_server_auth
                // Signed out already: the form says so.
                SignOutOthersOutcome.SignedOut, SignOutOthersOutcome.NotSignedIn -> null
            }
            actions.update { it.copy(signOutOthers = error?.let { e -> SignOutOthersState(error = e) }) }
        }
    }

    fun openPasswordChange() = actions.update { it.copy(passwordChange = it.passwordChange ?: PasswordChangeState(), notice = null) }

    fun cancelPasswordChange() = actions.update { if (it.passwordChange?.busy == true) it else it.copy(passwordChange = null) }

    fun onCurrentPassword(value: String) =
        actions.update { a -> a.copy(passwordChange = a.passwordChange?.copy(current = value, currentError = null, error = null)) }

    fun onNewPassword(value: String) =
        actions.update { a -> a.copy(passwordChange = a.passwordChange?.copy(new = value, newError = null, error = null)) }

    /**
     * Changes the password ([SyncAccount.changePassword]) after the length
     * rule is checked here too; the server signs every other device out. A
     * wrong current password is said under its field and signs nothing out.
     */
    fun confirmPasswordChange() {
        val current = actions.value.passwordChange ?: return
        if (current.busy) return
        if (current.current.isEmpty()) {
            return actions.update { it.copy(passwordChange = current.copy(currentError = Res.string.sync_change_password_error_current)) }
        }
        if (current.new.length !in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH) {
            return actions.update { it.copy(passwordChange = current.copy(newError = Res.string.sync_error_weak_password)) }
        }
        actions.update { it.copy(passwordChange = current.copy(busy = true, currentError = null, newError = null, error = null)) }
        viewModelScope.launch {
            val outcome = account.changePassword(current.current, current.new)
            actions.update { a ->
                val open = a.passwordChange?.copy(busy = false) ?: return@update a
                when (outcome) {
                    ChangePasswordOutcome.CHANGED -> a.copy(passwordChange = null, notice = AccountNotice.PasswordChanged)
                    ChangePasswordOutcome.WRONG_PASSWORD -> a.copy(passwordChange = open.copy(currentError = Res.string.sync_change_password_error_wrong))
                    ChangePasswordOutcome.WEAK_PASSWORD -> a.copy(passwordChange = open.copy(newError = Res.string.sync_error_weak_password))
                    ChangePasswordOutcome.NO_PASSWORD -> a.copy(passwordChange = open.copy(error = Res.string.sync_change_password_error_no_password))
                    ChangePasswordOutcome.RATE_LIMITED -> a.copy(passwordChange = open.copy(error = Res.string.sync_error_rate_limited))
                    ChangePasswordOutcome.NOT_A_SERVER -> a.copy(passwordChange = open.copy(error = Res.string.sync_account_error_old_server))
                    ChangePasswordOutcome.NETWORK -> a.copy(passwordChange = open.copy(error = Res.string.sync_error_unreachable))
                    ChangePasswordOutcome.SERVER -> a.copy(passwordChange = open.copy(error = Res.string.sync_error_server_auth))
                    // Signed out already: the form says so.
                    ChangePasswordOutcome.SIGNED_OUT, ChangePasswordOutcome.NOT_SIGNED_IN -> a.copy(passwordChange = null)
                }
            }
        }
    }

    private fun message(failure: AuthFailure): StringResource = when (failure) {
        AuthFailure.INVALID_CREDENTIALS -> Res.string.sync_error_credentials
        AuthFailure.ACCOUNT_EXISTS -> Res.string.sync_error_exists
        AuthFailure.WEAK_PASSWORD -> Res.string.sync_error_weak_password
        AuthFailure.INVALID_REQUEST -> Res.string.sync_error_invalid
        AuthFailure.RATE_LIMITED -> Res.string.sync_error_rate_limited
        AuthFailure.NOT_A_SERVER -> Res.string.sync_error_not_a_server
        AuthFailure.NETWORK -> Res.string.sync_error_unreachable
        AuthFailure.SERVER -> Res.string.sync_error_server_auth
        AuthFailure.GOOGLE_REJECTED -> Res.string.sync_error_google_rejected
        AuthFailure.GOOGLE_EMAIL_UNVERIFIED -> Res.string.sync_error_google_unverified
        AuthFailure.GOOGLE_ACCOUNT_MISMATCH -> Res.string.sync_error_google_mismatch
        AuthFailure.GOOGLE_NOT_OFFERED -> Res.string.sync_error_google_not_offered
        AuthFailure.APPLE_REJECTED -> Res.string.sync_error_apple_rejected
        AuthFailure.APPLE_EMAIL_UNVERIFIED -> Res.string.sync_error_apple_unverified
        AuthFailure.APPLE_ACCOUNT_MISMATCH -> Res.string.sync_error_apple_mismatch
        AuthFailure.APPLE_NOT_OFFERED -> Res.string.sync_error_apple_not_offered
    }
}
