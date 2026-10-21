package io.github.kanylbullen.tung.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.TungApp
import io.github.kanylbullen.tung.core.sync.SyncFailure
import io.github.kanylbullen.tung.core.sync.SyncState
import io.github.kanylbullen.tung.sync.AccountState
import io.github.kanylbullen.tung.ui.common.PrimaryButton
import io.github.kanylbullen.tung.ui.common.SecondaryButton
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.factoryOf
import io.github.kanylbullen.tung.ui.common.localDate
import io.github.kanylbullen.tung.ui.common.rememberNow
import io.github.kanylbullen.tung.ui.common.tungFieldColors
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner

/**
 * Settings' Account (CLAUDE.md, "Sync", "Account"): signed out, Google first
 * (when the server offers it), then email and password, and the server at the
 * bottom, read-only until "Use another server"; signed in, who, Google linked,
 * the last sync, "Sync now" and "Sign out".
 */
@Composable
fun AccountScreen(onBack: () -> Unit) {
    val container = appContainer()
    // Credential Manager shows Google's account sheet over this activity.
    val context = LocalContext.current
    val app = context.applicationContext as TungApp
    val vm: AccountViewModel = viewModel(factory = factoryOf { AccountViewModel(container.syncAccount, app.defaultServerUrl) })
    val state by vm.state.collectAsStateWithLifecycle()
    val google = remember { app.createGoogleSignIn() }

    SettingsPage(title = stringResource(R.string.settings_group_account), onBack = onBack) {
        val account = state.account
        when (account?.signedIn) {
            null -> Unit
            true -> SignedIn(account, onSyncNow = vm::syncNow, onSignOut = vm::signOut)
            false -> SignInForm(
                form = state.form,
                lastError = account.sync.lastError,
                onUseAnotherServer = vm::useAnotherServer,
                onServerUrl = vm::onServerUrl,
                onEmail = vm::onEmail,
                onPassword = vm::onPassword,
                onSignIn = { vm.signIn(create = false) },
                onCreate = { vm.signIn(create = true) },
                onGoogle = { vm.signInWithGoogle { clientId -> google.idToken(context, clientId) } },
            )
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
        if (account.googleLinked) {
            Text(stringResource(R.string.sync_google_linked), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
        }
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

@Composable
private fun SignInForm(
    form: SignInForm,
    lastError: String?,
    onUseAnotherServer: () -> Unit,
    onServerUrl: (String) -> Unit,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSignIn: () -> Unit,
    onCreate: () -> Unit,
    onGoogle: () -> Unit,
) {
    Text(stringResource(R.string.sync_intro), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
    // After the server ended the session, say so above the form.
    if (SyncFailure.fromKey(lastError) == SyncFailure.SIGNED_OUT) {
        Text(stringResource(R.string.sync_error_signed_out), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.accentText)
    }
    // Only when this server offers it (`/auth/methods`, asked as the screen opens); the client id comes from there.
    if (form.googleClientId != null) {
        GoogleButton(onClick = onGoogle, enabled = !form.busy)
        Text(stringResource(R.string.sync_google_or_email), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
    }
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
    if (form.editingServer) {
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
    } else {
        // The public server (or the stored one), read-only: most people never need another.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.sync_server_url), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
            Text(form.serverUrl, style = MaterialTheme.typography.bodyLarge, color = Tung.colors.text)
        }
        TextButton(onClick = onUseAnotherServer, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.sync_use_another_server), color = Tung.colors.text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * "Sign in with Google": an outlined button with the text, in the app's own
 * colours (Google's branding allows a neutral button; one accent stays the
 * primary "Sign in").
 */
@Composable
private fun GoogleButton(onClick: () -> Unit, enabled: Boolean) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = TungCorner,
        border = BorderStroke(1.dp, Tung.colors.border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Tung.colors.text, disabledContentColor = Tung.colors.muted),
    ) {
        Text(stringResource(R.string.sync_google_sign_in), style = MaterialTheme.typography.labelLarge)
    }
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
