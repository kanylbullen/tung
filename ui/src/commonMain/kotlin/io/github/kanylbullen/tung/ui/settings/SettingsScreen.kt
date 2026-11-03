package io.github.kanylbullen.tung.ui.settings

import io.github.kanylbullen.tung.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.StringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kanylbullen.tung.core.sync.PushChoice
import io.github.kanylbullen.tung.ui.common.dateText
import io.github.kanylbullen.tung.ui.theme.Tung

/** Settings' groups, in the order the list shows them; each opens its own screen at [route]. */
enum class SettingsGroup(val route: String, val title: StringResource) {
    ACCOUNT("settings/account", Res.string.settings_group_account),
    DEVICES("settings/devices", Res.string.settings_group_devices),
    TRAINING("settings/training", Res.string.settings_group_training),
    ASSISTANT("settings/assistant", Res.string.settings_group_assistant),
    HEALTH_CONNECT("settings/health", Res.string.settings_group_health),
    DATA("settings/data", Res.string.settings_group_data),
    FEEDBACK("settings/feedback", Res.string.settings_group_feedback),
    ABOUT("settings/about", Res.string.settings_group_about),
}

/**
 * Settings, reached from Home's gear: the groups as calm list rows, a title
 * and a one-line live summary each ("Signed in as …", "Push: FCM",
 * "Default rest 1:30 · kg"). A tap opens the group's own screen. What only
 * the platform knows comes in: [version] and [buildType] for About, and
 * [healthSummary] for the health row (Health Connect on Android), which is
 * left out where [showHealth] is false (no health store to open).
 */
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    version: String,
    buildType: String,
    healthSummary: String?,
    showHealth: Boolean,
    onBack: () -> Unit,
    onOpenGroup: (SettingsGroup) -> Unit,
) {
    val summary by vm.state.collectAsStateWithLifecycle()

    SettingsPage(title = stringResource(Res.string.settings_title), onBack = onBack, padded = false) {
        SettingsGroup.entries.filter { it != SettingsGroup.HEALTH_CONNECT || showHealth }.forEach { group ->
            val line = when (group) {
                SettingsGroup.ACCOUNT -> accountSummary(summary)
                SettingsGroup.DEVICES -> devicesSummary(summary)
                SettingsGroup.TRAINING -> summary.restDefaultSeconds?.let {
                    stringResource(Res.string.settings_summary_training, restText(it))
                }
                SettingsGroup.ASSISTANT -> summary.ai?.let { ai ->
                    if (ai.configured) ai.model.trim() else stringResource(Res.string.settings_summary_not_set_up)
                }
                SettingsGroup.HEALTH_CONNECT -> healthSummary
                SettingsGroup.DATA -> summary.lastImport?.let { stringResource(Res.string.settings_summary_last_import, shortDate(it.at)) }
                    ?: stringResource(Res.string.settings_summary_data)
                SettingsGroup.FEEDBACK -> stringResource(Res.string.settings_summary_feedback)
                SettingsGroup.ABOUT -> stringResource(Res.string.settings_summary_about, version, buildType)
            }
            GroupRow(stringResource(group.title), line.orEmpty()) { onOpenGroup(group) }
        }
    }
}

@Composable
private fun accountSummary(summary: SettingsSummary): String? {
    val account = summary.account ?: return null
    return when (account.signedIn) {
        null -> null
        true -> stringResource(Res.string.sync_signed_in_as, account.email.orEmpty())
        false -> stringResource(Res.string.settings_summary_signed_out)
    }
}

@Composable
private fun devicesSummary(summary: SettingsSummary): String? {
    val account = summary.account ?: return null
    if (account.signedIn != true) return stringResource(Res.string.settings_summary_devices_signed_out)
    return when (val choice = summary.push.choice) {
        null -> stringResource(Res.string.settings_summary_devices)
        PushChoice.Fcm -> stringResource(Res.string.push_status_fcm)
        PushChoice.Apns -> stringResource(Res.string.push_status_apns)
        is PushChoice.UnifiedPush -> stringResource(Res.string.push_status_unifiedpush, summary.push.distributorLabel ?: choice.distributor)
        PushChoice.None -> stringResource(Res.string.push_status_none)
    }
}

/** "21 Oct" (with the year when it is not this one): the date of a recent thing, in the app's language. */
private fun shortDate(epochMillis: Long): String = dateText(epochMillis, "d MMM", "d MMM yyyy")

/** A group of Settings as one list row: its name, and what it is set to below. */
@Composable
private fun GroupRow(title: String, summary: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Tung.colors.text)
        if (summary.isNotEmpty()) {
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted, maxLines = 1)
        }
    }
}

/**
 * One page of Settings: a top bar with back and [title], and the [content]
 * in a scrolling column, spaced like the rest of the app ([padded] false for
 * full-width list rows).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(title: String, onBack: () -> Unit, padded: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                title = { Text(title) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .then(if (padded) Modifier.padding(horizontal = 20.dp, vertical = 8.dp) else Modifier.padding(vertical = 8.dp)),
            verticalArrangement = Arrangement.spacedBy(if (padded) 16.dp else 0.dp),
            content = content,
        )
    }
}
