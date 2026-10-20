package io.github.kanylbullen.tung.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.sync.ServerUrlCheck
import io.github.kanylbullen.tung.core.sync.checkServerUrl
import io.github.kanylbullen.tung.sync.AccountState
import io.github.kanylbullen.tung.sync.AuthFailure
import io.github.kanylbullen.tung.sync.AuthResult
import io.github.kanylbullen.tung.sync.SyncAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
)

data class SettingsUiState(
    /** Null until the account has been read. */
    val account: AccountState? = null,
    val form: SignInForm = SignInForm(),
)

/**
 * Settings: the server and the account. Sign-in, sign-out and "Sync now" go
 * through [SyncAccount]; the screen never sees the token.
 */
class SettingsViewModel(private val account: SyncAccount) : ViewModel() {

    private val form = MutableStateFlow(SignInForm())

    val state: StateFlow<SettingsUiState> = combine(account.state, form) { a, f -> SettingsUiState(a, f) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        // The last server and email, so signing in again is one password away.
        viewModelScope.launch {
            val stored = account.state.first()
            form.update {
                it.copy(
                    serverUrl = it.serverUrl.ifEmpty { stored.serverUrl.orEmpty() },
                    email = it.email.ifEmpty { stored.email.orEmpty() },
                )
            }
        }
    }

    fun onServerUrl(value: String) = form.update { it.copy(serverUrl = value, error = null) }

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

    fun syncNow() {
        viewModelScope.launch { account.syncNow() }
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
    }
}
