package io.github.kanylbullen.tung.ui.settings

import io.github.kanylbullen.tung.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.window.DialogProperties
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kanylbullen.tung.auth.GoogleSignInResult
import io.github.kanylbullen.tung.core.sync.SyncFailure
import io.github.kanylbullen.tung.core.sync.SyncState
import io.github.kanylbullen.tung.sync.AccountState
import io.github.kanylbullen.tung.ui.common.PrimaryButton
import io.github.kanylbullen.tung.ui.common.SecondaryButton
import io.github.kanylbullen.tung.ui.common.localDate
import io.github.kanylbullen.tung.ui.common.rememberNow
import io.github.kanylbullen.tung.ui.common.tungFieldColors
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner

/**
 * Settings' Account (CLAUDE.md, "Sync", "Account"): signed out, Google first
 * (when the server offers it), then email and password, and the server at the
 * bottom, read-only until "Use another server"; signed in, who, Google linked,
 * the last sync, "Sync now" and "Sign out". [googleIdToken] asks the
 * platform's Google sign-in for an ID token for the server's client id.
 */
@Composable
fun AccountScreen(vm: AccountViewModel, onBack: () -> Unit, googleIdToken: suspend (clientId: String) -> GoogleSignInResult) {
    val state by vm.state.collectAsStateWithLifecycle()

    SettingsPage(title = stringResource(Res.string.settings_group_account), onBack = onBack) {
        val account = state.account
        when (account?.signedIn) {
            null -> Unit
            true -> SignedIn(
                account,
                resend = state.resend,
                onSyncNow = vm::syncNow,
                onResend = vm::resendConfirmation,
                onSignOut = vm::signOut,
                onDelete = vm::openDeletion,
            )
            false -> SignInForm(
                form = state.form,
                deleted = state.deleted,
                lastError = account.sync.lastError,
                onUseAnotherServer = vm::useAnotherServer,
                onServerUrl = vm::onServerUrl,
                onEmail = vm::onEmail,
                onPassword = vm::onPassword,
                onSignIn = { vm.signIn(create = false) },
                onCreate = { vm.signIn(create = true) },
                onForgot = vm::forgotPassword,
                onGoogle = { vm.signInWithGoogle(googleIdToken) },
            )
        }
    }
    val deletion = state.deletion
    if (deletion != null && state.account?.signedIn == true) {
        DeleteAccountDialog(
            serverUrl = state.account?.serverUrl.orEmpty(),
            deletion = deletion,
            onEmail = vm::onDeletionEmail,
            onConfirm = vm::confirmDeletion,
            onDismiss = vm::cancelDeletion,
        )
    }
}

@Composable
private fun SignedIn(
    account: AccountState,
    resend: ResendState?,
    onSyncNow: () -> Unit,
    onResend: () -> Unit,
    onSignOut: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(Res.string.sync_signed_in_as, account.email.orEmpty()),
            style = MaterialTheme.typography.bodyLarge,
            color = Tung.colors.text,
        )
        Text(account.serverUrl.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
        if (account.googleLinked) {
            Text(stringResource(Res.string.sync_google_linked), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
        }
    }
    // A new account on a server that confirms addresses: nothing syncs until the emailed link is opened.
    if (SyncFailure.fromKey(account.sync.lastError) == SyncFailure.EMAIL_NOT_VERIFIED) {
        Text(
            stringResource(Res.string.sync_confirm_email, account.email.orEmpty()),
            style = MaterialTheme.typography.bodyLarge,
            color = Tung.colors.accentText,
        )
        SecondaryButton(text = stringResource(Res.string.sync_confirm_resend), onClick = onResend, enabled = resend != ResendState.Sending)
        (resend as? ResendState.Done)?.let {
            Text(stringResource(it.message), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
        }
    }
    Text(syncStatusText(account.sync), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
    PrimaryButton(
        text = stringResource(if (account.syncing) Res.string.sync_syncing else Res.string.sync_now),
        onClick = onSyncNow,
        enabled = !account.syncing,
    )
    SecondaryButton(text = stringResource(Res.string.sync_sign_out), onClick = onSignOut)
    Text(stringResource(Res.string.sync_sign_out_hint), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
    // Quiet and last: a destructive action that asks before it does anything.
    TextButton(onClick = onDelete) {
        Text(stringResource(Res.string.sync_delete_account), color = Tung.colors.muted)
    }
}

/**
 * "Delete your account?": what goes (everything on [serverUrl]) and what
 * stays (the workouts on this phone), the account's email typed to confirm,
 * and the server's answer under the field.
 */
@Composable
private fun DeleteAccountDialog(
    serverUrl: String,
    deletion: AccountDeletion,
    onEmail: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // A dialog with a text field: Compose's width, or it never settles under Robolectric (CLAUDE.md, "Flow tests").
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 24.dp),
        containerColor = Tung.colors.raised,
        tonalElevation = 0.dp,
        shape = TungCorner,
        title = { Text(stringResource(Res.string.sync_delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.sync_delete_body, serverUrl), color = Tung.colors.muted)
                OutlinedTextField(
                    value = deletion.email,
                    onValueChange = onEmail,
                    label = { Text(stringResource(Res.string.sync_delete_email)) },
                    singleLine = true,
                    enabled = !deletion.busy,
                    isError = deletion.error != null,
                    supportingText = deletion.error?.let { error -> { Text(stringResource(error)) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                    shape = TungCorner,
                    colors = tungFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !deletion.busy) {
                Text(stringResource(Res.string.sync_delete_confirm), color = Tung.colors.accentText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deletion.busy) {
                Text(stringResource(Res.string.action_cancel), color = Tung.colors.text)
            }
        },
    )
}

@Composable
private fun SignInForm(
    form: SignInForm,
    deleted: Boolean,
    lastError: String?,
    onUseAnotherServer: () -> Unit,
    onServerUrl: (String) -> Unit,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSignIn: () -> Unit,
    onCreate: () -> Unit,
    onForgot: () -> Unit,
    onGoogle: () -> Unit,
) {
    Text(stringResource(Res.string.sync_intro), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
    if (deleted) {
        Text(stringResource(Res.string.sync_delete_done), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.accentText)
    }
    // After the server ended the session, say so above the form.
    if (SyncFailure.fromKey(lastError) == SyncFailure.SIGNED_OUT) {
        Text(stringResource(Res.string.sync_error_signed_out), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.accentText)
    }
    // Only when this server offers it (`/auth/methods`, asked as the screen opens); the client id comes from there.
    if (form.googleClientId != null) {
        GoogleButton(onClick = onGoogle, enabled = !form.busy)
        Text(stringResource(Res.string.sync_google_or_email), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
    }
    OutlinedTextField(
        value = form.email,
        onValueChange = onEmail,
        label = { Text(stringResource(Res.string.sync_email)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
        shape = TungCorner,
        colors = tungFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.password,
        onValueChange = onPassword,
        label = { Text(stringResource(Res.string.sync_password)) },
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
    PrimaryButton(text = stringResource(Res.string.sync_sign_in), onClick = onSignIn, enabled = !form.busy)
    SecondaryButton(text = stringResource(Res.string.sync_create_account), onClick = onCreate)
    TextButton(onClick = onForgot, enabled = !form.busy, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(Res.string.sync_forgot_password), color = Tung.colors.text, style = MaterialTheme.typography.labelLarge)
    }
    form.notice?.let {
        Text(stringResource(it, form.email.trim()), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
    }
    if (form.editingServer) {
        OutlinedTextField(
            value = form.serverUrl,
            onValueChange = onServerUrl,
            label = { Text(stringResource(Res.string.sync_server_url)) },
            placeholder = { Text(stringResource(Res.string.sync_server_url_example)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            shape = TungCorner,
            colors = tungFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        // The public server (or the stored one), read-only: most people never need another.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(Res.string.sync_server_url), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
            Text(form.serverUrl, style = MaterialTheme.typography.bodyLarge, color = Tung.colors.text)
        }
        TextButton(onClick = onUseAnotherServer, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(Res.string.sync_use_another_server), color = Tung.colors.text, style = MaterialTheme.typography.labelLarge)
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
        Text(stringResource(Res.string.sync_google_sign_in), style = MaterialTheme.typography.labelLarge)
    }
}

/** "Last sync 2 min ago", "Last sync: never", or the last failure in words. */
@Composable
private fun syncStatusText(sync: SyncState): String {
    when (SyncFailure.fromKey(sync.lastError)) {
        SyncFailure.NETWORK -> return stringResource(Res.string.sync_error_network)
        SyncFailure.SERVER -> return stringResource(Res.string.sync_error_server)
        SyncFailure.SIGNED_OUT -> return stringResource(Res.string.sync_error_signed_out)
        SyncFailure.REJECTED -> return stringResource(Res.string.sync_error_rejected)
        SyncFailure.EMAIL_NOT_VERIFIED -> return stringResource(Res.string.sync_error_email_not_verified)
        null -> Unit
    }
    val at = sync.lastSyncAt ?: return stringResource(Res.string.sync_status_never)
    val now = rememberNow(intervalMillis = 30_000L)
    val minutes = ((now - at).coerceAtLeast(0L) / 60_000L).toInt()
    return when {
        minutes < 1 -> stringResource(Res.string.sync_status_just_now)
        minutes < 60 -> pluralStringResource(Res.plurals.sync_status_minutes, minutes, minutes)
        minutes < 24 * 60 -> pluralStringResource(Res.plurals.sync_status_hours, minutes / 60, minutes / 60)
        else -> stringResource(Res.string.sync_status_date, localDate(at))
    }
}
