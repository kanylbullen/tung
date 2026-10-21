package io.github.kanylbullen.tung.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.push.PushRegistrar
import io.github.kanylbullen.tung.push.PushStatus
import io.github.kanylbullen.tung.sync.LinkCode
import io.github.kanylbullen.tung.sync.LinkFailure
import io.github.kanylbullen.tung.sync.LinkStartResult
import io.github.kanylbullen.tung.sync.SyncAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * "Link a watch": the code from the server while it is shown, and why there
 * is none. [error] is a string resource.
 */
data class WatchLinkState(
    val code: LinkCode? = null,
    val busy: Boolean = false,
    @StringRes val error: Int? = null,
)

data class DevicesUiState(
    /** Null until the account has been read; both need an account. */
    val signedIn: Boolean? = null,
    val watchLink: WatchLinkState = WatchLinkState(),
    /** How the server wakes this phone: FCM, a UnifiedPush distributor, or nothing. */
    val push: PushStatus = PushStatus(),
)

/** Settings' Devices: "Link a watch" and the push transport, both for a signed-in account. */
class DevicesViewModel(private val account: SyncAccount, private val push: PushRegistrar) : ViewModel() {

    private val watchLink = MutableStateFlow(WatchLinkState())

    val state: StateFlow<DevicesUiState> = combine(account.state, watchLink, push.status) { a, w, p -> DevicesUiState(a.signedIn, w, p) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesUiState())

    init {
        // A distributor installed since the app started shows up here, and gets used.
        viewModelScope.launch { if (account.isSignedIn()) push.refresh() }
    }

    /** "Choose UnifiedPush distributor", when several are installed. */
    fun chooseDistributor(packageName: String) {
        viewModelScope.launch { push.chooseDistributor(packageName) }
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
}
