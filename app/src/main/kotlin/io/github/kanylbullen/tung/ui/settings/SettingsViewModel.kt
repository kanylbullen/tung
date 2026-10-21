package io.github.kanylbullen.tung.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.ai.AiConfig
import io.github.kanylbullen.tung.ai.AiSettings
import io.github.kanylbullen.tung.importing.ImportService
import io.github.kanylbullen.tung.importing.LastImport
import io.github.kanylbullen.tung.push.PushRegistrar
import io.github.kanylbullen.tung.push.PushStatus
import io.github.kanylbullen.tung.sync.AccountState
import io.github.kanylbullen.tung.sync.SyncAccount
import io.github.kanylbullen.tung.timer.RestTimerController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What the group rows of Settings say, one line each; null fields are not read yet. */
data class SettingsSummary(
    val account: AccountState? = null,
    val push: PushStatus = PushStatus(),
    val restDefaultSeconds: Int? = null,
    val ai: AiConfig? = null,
    val lastImport: LastImport? = null,
)

/**
 * Settings' list of groups: only reads, for the summaries. Each group's own
 * screen has its own ViewModel; this one never asks a server (the Account
 * screen asks `/auth/methods`, not the list).
 */
class SettingsViewModel(
    account: SyncAccount,
    push: PushRegistrar,
    restTimer: RestTimerController,
    aiSettings: AiSettings,
    imports: ImportService,
) : ViewModel() {

    val state: StateFlow<SettingsSummary> = combine(
        account.state,
        push.status,
        restTimer.defaultSeconds,
        aiSettings.config,
        imports.lastImport,
    ) { a, p, rest, ai, last -> SettingsSummary(a, p, rest, ai, last) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsSummary())
}
