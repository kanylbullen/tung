package io.github.kanylbullen.tung.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.sync.SyncFailure
import io.github.kanylbullen.tung.core.sync.SyncState
import io.github.kanylbullen.tung.sync.AccountState
import io.github.kanylbullen.tung.ui.common.PrimaryButton
import io.github.kanylbullen.tung.ui.common.SecondaryButton
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.clockText
import io.github.kanylbullen.tung.ui.common.factoryOf
import io.github.kanylbullen.tung.ui.common.localDate
import io.github.kanylbullen.tung.ui.common.rememberNow
import io.github.kanylbullen.tung.ui.common.tungFieldColors
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner

/** Settings, reached from Home's gear: for now only the server and the account for sync. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val container = appContainer()
    val vm: SettingsViewModel = viewModel(factory = factoryOf { SettingsViewModel(container.syncAccount) })
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Tung.colors.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Tung.colors.background,
                    scrolledContainerColor = Tung.colors.background,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.settings_title)) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.sync_title), style = MaterialTheme.typography.titleMedium, color = Tung.colors.muted)
            val account = state.account
            when (account?.signedIn) {
                null -> Unit
                true -> {
                    SignedIn(account, onSyncNow = vm::syncNow, onSignOut = vm::signOut)
                    WatchLink(state.watchLink, onLink = vm::linkWatch, onClose = vm::closeWatchLink)
                }
                false -> SignInForm(
                    form = state.form,
                    lastError = account.sync.lastError,
                    onServerUrl = vm::onServerUrl,
                    onEmail = vm::onEmail,
                    onPassword = vm::onPassword,
                    onSignIn = { vm.signIn(create = false) },
                    onCreate = { vm.signIn(create = true) },
                )
            }
        }
    }
}

@Composable
private fun SignedIn(account: AccountState, onSyncNow: () -> Unit, onSignOut: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.sync_signed_in_as, account.email.orEmpty()),
            style = MaterialTheme.typography.bodyLarge,
            color = Tung.colors.text,
        )
        Text(account.serverUrl.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
    }
    Text(syncStatusText(account.sync), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
    PrimaryButton(
        text = stringResource(if (account.syncing) R.string.sync_syncing else R.string.sync_now),
        onClick = onSyncNow,
        enabled = !account.syncing,
    )
    SecondaryButton(text = stringResource(R.string.sync_sign_out), onClick = onSignOut)
    Text(stringResource(R.string.sync_sign_out_hint), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
}

/**
 * "Link a watch": a six-digit code from the server, shown large with what is
 * left of its ten minutes, for the watch to type on its number pad.
 */
@Composable
private fun WatchLink(link: WatchLinkState, onLink: () -> Unit, onClose: () -> Unit) {
    Text(stringResource(R.string.link_watch_title), style = MaterialTheme.typography.titleMedium, color = Tung.colors.muted)
    val code = link.code
    if (code == null) {
        Text(stringResource(R.string.link_watch_intro), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
        link.error?.let {
            Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.accentText)
        }
        SecondaryButton(text = stringResource(if (link.busy) R.string.link_watch_busy else R.string.link_watch), onClick = onLink)
        return
    }
    val now = rememberNow(intervalMillis = 1_000L)
    val left = ((code.expiresAt - now).coerceAtLeast(0L) + 999L) / 1_000L
    val spoken = code.code.toList().joinToString(" ")
    Text(
        text = code.code.chunked(3).joinToString(" "),
        style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
        color = if (left > 0) Tung.colors.text else Tung.colors.muted,
        modifier = Modifier.semantics { contentDescription = spoken },
    )
    if (left > 0) {
        Text(
            stringResource(R.string.link_watch_valid_for, clockText(left * 1_000L)),
            style = MaterialTheme.typography.bodyMedium,
            color = Tung.colors.muted,
        )
        Text(stringResource(R.string.link_watch_hint), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
        SecondaryButton(text = stringResource(R.string.link_watch_done), onClick = onClose)
    } else {
        Text(stringResource(R.string.link_watch_expired), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.accentText)
        SecondaryButton(text = stringResource(R.string.link_watch_new_code), onClick = onLink)
    }
}

@Composable
private fun SignInForm(
    form: SignInForm,
    lastError: String?,
    onServerUrl: (String) -> Unit,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSignIn: () -> Unit,
    onCreate: () -> Unit,
) {
    Text(stringResource(R.string.sync_intro), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
    // After the server ended the session, say so above the form.
    if (SyncFailure.fromKey(lastError) == SyncFailure.SIGNED_OUT) {
        Text(stringResource(R.string.sync_error_signed_out), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.accentText)
    }
    OutlinedTextField(
        value = form.serverUrl,
        onValueChange = onServerUrl,
        label = { Text(stringResource(R.string.sync_server_url)) },
        placeholder = { Text(stringResource(R.string.sync_server_url_example)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        shape = TungCorner,
        colors = tungFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.email,
        onValueChange = onEmail,
        label = { Text(stringResource(R.string.sync_email)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
        shape = TungCorner,
        colors = tungFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.password,
        onValueChange = onPassword,
        label = { Text(stringResource(R.string.sync_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        shape = TungCorner,
        colors = tungFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    form.error?.let {
        Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.accentText)
    }
    PrimaryButton(text = stringResource(R.string.sync_sign_in), onClick = onSignIn, enabled = !form.busy)
    SecondaryButton(text = stringResource(R.string.sync_create_account), onClick = onCreate)
}

/** "Last sync 2 min ago", "Last sync: never", or the last failure in words. */
@Composable
private fun syncStatusText(sync: SyncState): String {
    when (SyncFailure.fromKey(sync.lastError)) {
        SyncFailure.NETWORK -> return stringResource(R.string.sync_error_network)
        SyncFailure.SERVER -> return stringResource(R.string.sync_error_server)
        SyncFailure.SIGNED_OUT -> return stringResource(R.string.sync_error_signed_out)
        SyncFailure.REJECTED -> return stringResource(R.string.sync_error_rejected)
        null -> Unit
    }
    val at = sync.lastSyncAt ?: return stringResource(R.string.sync_status_never)
    val now = rememberNow(intervalMillis = 30_000L)
    val minutes = ((now - at).coerceAtLeast(0L) / 60_000L).toInt()
    return when {
        minutes < 1 -> stringResource(R.string.sync_status_just_now)
        minutes < 60 -> pluralStringResource(R.plurals.sync_status_minutes, minutes, minutes)
        minutes < 24 * 60 -> pluralStringResource(R.plurals.sync_status_hours, minutes / 60, minutes / 60)
        else -> stringResource(R.string.sync_status_date, localDate(at))
    }
}
