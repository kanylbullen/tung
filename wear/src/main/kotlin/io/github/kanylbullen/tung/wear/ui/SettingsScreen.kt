package io.github.kanylbullen.tung.wear.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import io.github.kanylbullen.tung.core.sync.SyncFailure
import io.github.kanylbullen.tung.wear.AccountUi
import io.github.kanylbullen.tung.wear.R
import java.net.URI

/** A server URL as a person reads it: the host (and port), without the scheme. */
fun serverLabel(url: String): String =
    runCatching { URI(url) }.getOrNull()?.let { uri -> uri.host?.let { host -> if (uri.port == -1) host else "$host:${uri.port}" } } ?: url

/**
 * Settings, from the no-workout screen: signed in, the account, "Sync now"
 * with the last sync, and "Sign out"; not signed in, the way to link the watch.
 */
@Composable
fun SettingsScreen(
    ui: AccountUi,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
    onLinkTo: (String) -> Unit,
    onChooseServer: () -> Unit,
) {
    val account = ui.account
    val signedIn = account?.signedIn
    if (account == null || signedIn == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(indicatorColor = TungColors.accent)
        }
        return
    }
    val listState = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text(stringResource(R.string.settings_title), color = TungColors.muted) } }
            if (signedIn) {
                item { Note(stringResource(R.string.settings_signed_in, account.email.orEmpty()), TungColors.text) }
                account.serverUrl?.let { url -> item { Note(stringResource(R.string.settings_server, serverLabel(url)), TungColors.muted) } }
                item { Note(syncLine(ui), if (ui.syncFailure != null || account.sync.lastError != null) TungColors.accent else TungColors.muted) }
                item {
                    Chip(
                        label = { Text(stringResource(if (account.syncing) R.string.sync_syncing else R.string.sync_now)) },
                        onClick = { if (!account.syncing) onSyncNow() },
                        colors = accentChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Chip(
                        label = { Text(stringResource(R.string.sign_out)) },
                        onClick = onSignOut,
                        colors = raisedChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                item { Note(stringResource(R.string.standalone_explainer), TungColors.muted) }
                val phone = ui.phoneServer
                if (phone != null) {
                    item {
                        Chip(
                            label = { Text(stringResource(R.string.link_to_server, serverLabel(phone)), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            secondaryLabel = { Text(stringResource(R.string.server_from_phone)) },
                            onClick = { onLinkTo(phone) },
                            colors = accentChipColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    Chip(
                        label = { Text(stringResource(if (phone != null) R.string.link_choose_server else R.string.link_a_server)) },
                        onClick = onChooseServer,
                        colors = if (phone != null) raisedChipColors() else accentChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun syncLine(ui: AccountUi): String {
    val context = LocalContext.current
    val sync = ui.account?.sync
    val failure = ui.syncFailure ?: SyncFailure.fromKey(sync?.lastError)
    if (failure != null) {
        return stringResource(
            when (failure) {
                SyncFailure.NETWORK -> R.string.sync_error_network
                SyncFailure.SERVER -> R.string.sync_error_server
                SyncFailure.SIGNED_OUT -> R.string.sync_error_signed_out
                SyncFailure.REJECTED -> R.string.sync_error_rejected
            },
        )
    }
    val at = sync?.lastSyncAt ?: return stringResource(R.string.sync_never)
    val time = DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL)
    return stringResource(R.string.sync_last, time)
}

@Composable
private fun Note(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        color = color,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.caption1,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    )
}

/**
 * The server to link to: the phone's (the normal way, no typing), the ones
 * this watch used before, or one typed in, which is slow on a watch.
 */
@Composable
fun ServerScreen(
    ui: AccountUi,
    onPick: (String) -> Unit,
    onTyped: (String) -> Int?,
) {
    val listState = rememberScalingLazyListState()
    var typed by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<Int?>(null) }
    fun useTyped() {
        error = onTyped(typed)
    }
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text(stringResource(R.string.servers_title), color = TungColors.muted) } }
            items(ui.servers, key = { it }) { url ->
                Chip(
                    label = { Text(serverLabel(url), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = if (url == ui.phoneServer) {
                        { Text(stringResource(R.string.server_from_phone)) }
                    } else {
                        null
                    },
                    onClick = { onPick(url) },
                    colors = if (url == ui.phoneServer) accentChipColors() else raisedChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (ui.phoneServer == null) item { Note(stringResource(R.string.server_typing_hint), TungColors.muted) }
            item {
                BasicTextField(
                    value = typed,
                    onValueChange = {
                        typed = it
                        error = null
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = TungColors.text, fontSize = 14.sp, fontFamily = Archivo, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(TungColors.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { useTyped() }),
                    decorationBox = { field ->
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (typed.isEmpty()) Text(stringResource(R.string.server_other), color = TungColors.muted)
                            field()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (typed.isNotBlank()) {
                item {
                    Chip(
                        label = { Text(stringResource(R.string.server_use)) },
                        onClick = { useTyped() },
                        colors = accentChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            error?.let { item { Note(stringResource(it), TungColors.accent) } }
        }
    }
}

