package io.github.kanylbullen.tung.wear

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.core.sync.ServerUrlCheck
import io.github.kanylbullen.tung.core.sync.SyncFailure
import io.github.kanylbullen.tung.data.TungHost
import io.github.kanylbullen.tung.sync.AccountState
import io.github.kanylbullen.tung.sync.LinkClaimResult
import io.github.kanylbullen.tung.sync.LinkFailure
import io.github.kanylbullen.tung.sync.SyncResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The watch's account and the servers it can link to. */
data class AccountUi(
    /** Null until the account has been read. */
    val account: AccountState? = null,
    /** The server the phone is signed in to, from the Data Layer; null without a phone or a signed-in phone. */
    val phoneServer: String? = null,
    /** What to offer when linking: the phone's server first, then the ones used before. */
    val servers: List<String> = emptyList(),
    /** "Sync now" failed with this; null otherwise. */
    val syncFailure: SyncFailure? = null,
)

/** The link screen: the server it links to and the code typed so far. [error] is a string resource. */
data class LinkUi(
    val server: String? = null,
    val input: LinkCodeInput = LinkCodeInput(),
    val busy: Boolean = false,
    @StringRes val error: Int? = null,
    /** Signed in: the screen goes back and the watch switches to standalone. */
    val linked: Boolean = false,
)

/**
 * Settings on the watch: the account, "Sync now", "Sign out", and linking with
 * a code from the phone (`SyncAccount.signInWithCode`). The watch never asks
 * for a password, and the token never leaves the data layer.
 */
class AccountViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as TungHost).container
    private val account = container.syncAccount
    private val syncFailure = MutableStateFlow<SyncFailure?>(null)

    private val phoneServer = PhoneLink(application).states().map { it?.serverUrl }.onStart { emit(null) }

    val ui: StateFlow<AccountUi> =
        combine(account.state, phoneServer, container.syncSettings.recentServers, syncFailure) { a, phone, recent, failure ->
            AccountUi(a, phone, serverChoices(phone, recent), failure)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountUi())

    private val _link = MutableStateFlow(LinkUi())
    val link: StateFlow<LinkUi> = _link

    fun syncNow() {
        syncFailure.value = null
        viewModelScope.launch {
            val result = account.syncNow()
            syncFailure.value = (result as? SyncResult.Failed)?.failure
        }
    }

    fun signOut() {
        viewModelScope.launch { account.signOut() }
    }

    /** Starts linking to [server], an address [checkWatchServerInput] accepted. */
    fun beginLink(server: String) {
        _link.value = LinkUi(server = server)
    }

    /**
     * A server typed on the watch: begins linking to it, or returns the
     * string resource that says why not.
     */
    @StringRes
    fun beginLinkTyped(text: String): Int? = when (val check = checkWatchServerInput(text)) {
        is ServerUrlCheck.Valid -> {
            beginLink(check.url)
            null
        }
        ServerUrlCheck.Malformed -> R.string.server_error_url
        ServerUrlCheck.NeedsHttps -> R.string.server_error_https
    }

    fun type(digit: Char) {
        val current = _link.value
        if (current.busy) return
        val next = current.copy(input = current.input.type(digit), error = null)
        _link.value = next
        // The sixth digit sends it: one tap less on a small screen.
        if (next.input.isComplete && !current.input.isComplete) submit()
    }

    fun delete() = _link.update { if (it.busy) it else it.copy(input = it.input.delete(), error = null) }

    fun submit() {
        val current = _link.value
        val server = current.server ?: return
        if (!current.input.isComplete || current.busy) return
        _link.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            when (val result = account.signInWithCode(server, current.input.digits)) {
                is LinkClaimResult.Success -> _link.update { it.copy(busy = false, linked = true) }
                is LinkClaimResult.Failed -> _link.update {
                    // A wrong code is cleared for the next try; anything else keeps it, to send again.
                    val keep = result.failure != LinkFailure.INVALID_CODE
                    it.copy(busy = false, input = if (keep) it.input else LinkCodeInput(), error = linkError(result.failure))
                }
            }
        }
    }

    @StringRes
    private fun linkError(failure: LinkFailure): Int = when (failure) {
        LinkFailure.INVALID_CODE -> R.string.link_error_code
        LinkFailure.RATE_LIMITED -> R.string.link_error_rate
        LinkFailure.NOT_A_SERVER -> R.string.link_error_not_server
        LinkFailure.NETWORK -> R.string.link_error_network
        LinkFailure.NOT_SIGNED_IN, LinkFailure.SIGNED_OUT, LinkFailure.SERVER -> R.string.link_error_server
    }
}
