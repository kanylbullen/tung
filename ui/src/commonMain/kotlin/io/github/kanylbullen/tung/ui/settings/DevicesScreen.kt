package io.github.kanylbullen.tung.ui.settings

import io.github.kanylbullen.tung.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kanylbullen.tung.core.sync.PushChoice
import io.github.kanylbullen.tung.push.PushStatus
import io.github.kanylbullen.tung.ui.common.SecondaryButton
import io.github.kanylbullen.tung.ui.common.urlHost
import io.github.kanylbullen.tung.ui.common.clockText
import io.github.kanylbullen.tung.ui.common.rememberNow
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner

/**
 * Settings' Devices: "Link a watch" (CLAUDE.md, "Wear") and how the server
 * wakes this phone (CLAUDE.md, "Ongoing workout and push"). Both need an
 * account; signed out, a hint and the way to Account.
 */
@Composable
fun DevicesScreen(vm: DevicesViewModel, onBack: () -> Unit, onOpenAccount: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()

    SettingsPage(title = stringResource(Res.string.settings_group_devices), onBack = onBack) {
        when (state.signedIn) {
            null -> Unit
            false -> {
                Text(stringResource(Res.string.settings_devices_signed_out), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
                SecondaryButton(text = stringResource(Res.string.settings_devices_open_account), onClick = onOpenAccount)
            }
            true -> {
                WatchLink(state.watchLink, onLink = vm::linkWatch, onClose = vm::closeWatchLink)
                Text(stringResource(Res.string.push_title), style = MaterialTheme.typography.titleMedium, color = Tung.colors.muted)
                Push(state.push, onChoose = vm::chooseDistributor)
            }
        }
    }
}

/**
 * How the server wakes this phone ("Push: FCM", "Push: ntfy", or none and the
 * 15-minute sync), whether the server has the endpoint, and the choice among
 * several UnifiedPush distributors.
 */
@Composable
private fun Push(push: PushStatus, onChoose: (String) -> Unit) {
    val choice = push.choice ?: return
    var choosing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            when (choice) {
                PushChoice.Fcm -> stringResource(Res.string.push_status_fcm)
                PushChoice.Apns -> stringResource(Res.string.push_status_apns)
                is PushChoice.UnifiedPush -> stringResource(Res.string.push_status_unifiedpush, push.distributorLabel ?: choice.distributor)
                PushChoice.None -> stringResource(Res.string.push_status_none)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = Tung.colors.text,
        )
        if (choice != PushChoice.None) {
            val host = push.endpoint?.takeIf { choice is PushChoice.UnifiedPush }?.let(::urlHost)
            Text(
                when {
                    !push.registered -> stringResource(Res.string.push_not_registered)
                    host != null -> stringResource(Res.string.push_registered_at, host)
                    else -> stringResource(Res.string.push_registered)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Tung.colors.muted,
            )
        }
    }
    if (choice is PushChoice.UnifiedPush && push.distributors.size > 1) {
        SecondaryButton(text = stringResource(Res.string.push_choose_distributor), onClick = { choosing = true })
    }
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            containerColor = Tung.colors.raised,
            tonalElevation = 0.dp,
            shape = TungCorner,
            title = { Text(stringResource(Res.string.push_choose_title)) },
            text = {
                Column {
                    push.distributors.forEach { distributor ->
                        TextButton(
                            onClick = {
                                choosing = false
                                onChoose(distributor.packageName)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            val current = (choice as? PushChoice.UnifiedPush)?.distributor == distributor.packageName
                            Text(
                                distributor.label,
                                color = if (current) Tung.colors.accentText else Tung.colors.text,
                                fontWeight = if (current) FontWeight.Bold else null,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { choosing = false }) {
                    Text(stringResource(Res.string.action_cancel), color = Tung.colors.text)
                }
            },
        )
    }
}

/**
 * "Link a watch": a six-digit code from the server, shown large with what is
 * left of its ten minutes, for the watch to type on its number pad.
 */
@Composable
private fun WatchLink(link: WatchLinkState, onLink: () -> Unit, onClose: () -> Unit) {
    Text(stringResource(Res.string.link_watch_title), style = MaterialTheme.typography.titleMedium, color = Tung.colors.muted)
    val code = link.code
    if (code == null) {
        Text(stringResource(Res.string.link_watch_intro), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
        link.error?.let {
            Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.accentText)
        }
        SecondaryButton(text = stringResource(if (link.busy) Res.string.link_watch_busy else Res.string.link_watch), onClick = onLink)
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
            stringResource(Res.string.link_watch_valid_for, clockText(left * 1_000L)),
            style = MaterialTheme.typography.bodyMedium,
            color = Tung.colors.muted,
        )
        Text(stringResource(Res.string.link_watch_hint), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
        SecondaryButton(text = stringResource(Res.string.link_watch_done), onClick = onClose)
    } else {
        Text(stringResource(Res.string.link_watch_expired), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.accentText)
        SecondaryButton(text = stringResource(Res.string.link_watch_new_code), onClick = onLink)
    }
}


