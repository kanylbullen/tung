package io.github.kanylbullen.tung.ui.settings

import org.jetbrains.compose.resources.stringResource
import io.github.kanylbullen.tung.ui.resources.*
import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.BuildConfig
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.sync.PushChoice
import io.github.kanylbullen.tung.health.HealthAvailability
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.factoryOf
import io.github.kanylbullen.tung.ui.theme.Tung
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Settings' groups, in the order the list shows them; each opens its own screen at [route]. */
enum class SettingsGroup(val route: String, @StringRes val title: Int) {
    ACCOUNT("settings/account", R.string.settings_group_account),
    DEVICES("settings/devices", R.string.settings_group_devices),
    TRAINING("settings/training", R.string.settings_group_training),
    ASSISTANT("settings/assistant", R.string.settings_group_assistant),
    HEALTH_CONNECT("settings/health", R.string.settings_group_health),
    DATA("settings/data", R.string.settings_group_data),
    FEEDBACK("settings/feedback", R.string.settings_group_feedback),
    ABOUT("settings/about", R.string.settings_group_about),
}

/**
 * Settings, reached from Home's gear: the groups as calm list rows, a title
 * and a one-line live summary each ("Signed in as …", "Push: FCM",
 * "Default rest 1:30 · kg"). A tap opens the group's own screen.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenGroup: (SettingsGroup) -> Unit) {
    val container = appContainer()
    val vm: SettingsViewModel = viewModel(
        factory = factoryOf { SettingsViewModel(container.syncAccount, container.push, container.restTimer, container.aiSettings, container.imports) },
    )
    val summary by vm.state.collectAsStateWithLifecycle()
    val health: HealthConnectViewModel = viewModel(factory = factoryOf { HealthConnectViewModel(container.healthConnect, container.healthConnectSettings) })
    val healthState by health.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(health) {
        health.refresh()
        onPauseOrDispose { }
    }

    SettingsPage(title = stringResource(R.string.settings_title), onBack = onBack, padded = false) {
        SettingsGroup.entries.forEach { group ->
            val line = when (group) {
                SettingsGroup.ACCOUNT -> accountSummary(summary)
                SettingsGroup.DEVICES -> devicesSummary(summary)
                SettingsGroup.TRAINING -> summary.restDefaultSeconds?.let {
                    stringResource(R.string.settings_summary_training, restText(it))
                }
                SettingsGroup.ASSISTANT -> summary.ai?.let { ai ->
                    if (ai.configured) ai.model.trim() else stringResource(R.string.settings_summary_not_set_up)
                }
                SettingsGroup.HEALTH_CONNECT -> stringResource(
                    when {
                        healthState.availability != HealthAvailability.AVAILABLE -> R.string.settings_summary_health_unavailable
                        healthState.write && healthState.read -> R.string.settings_summary_health_both
                        healthState.write -> R.string.settings_summary_health_write
                        healthState.read -> R.string.settings_summary_health_read
                        else -> R.string.settings_summary_health_off
                    },
                )
                SettingsGroup.DATA -> summary.lastImport?.let { stringResource(R.string.settings_summary_last_import, shortDate(it.at)) }
                    ?: stringResource(R.string.settings_summary_data)
                SettingsGroup.FEEDBACK -> stringResource(R.string.settings_summary_feedback)
                SettingsGroup.ABOUT -> stringResource(R.string.settings_summary_about, BuildConfig.VERSION_NAME, BuildConfig.BUILD_TYPE)
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
        true -> stringResource(R.string.sync_signed_in_as, account.email.orEmpty())
        false -> stringResource(R.string.settings_summary_signed_out)
    }
}

@Composable
private fun devicesSummary(summary: SettingsSummary): String? {
    val account = summary.account ?: return null
    if (account.signedIn != true) return stringResource(R.string.settings_summary_devices_signed_out)
    return when (val choice = summary.push.choice) {
        null -> stringResource(R.string.settings_summary_devices)
        PushChoice.Fcm -> stringResource(R.string.push_status_fcm)
        is PushChoice.UnifiedPush -> stringResource(R.string.push_status_unifiedpush, summary.push.distributorLabel ?: choice.distributor)
        PushChoice.None -> stringResource(R.string.push_status_none)
    }
}

/** "21 Oct": the date of a recent thing, in the app's language. */
private fun shortDate(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

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
