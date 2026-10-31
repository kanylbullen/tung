package io.github.kanylbullen.tung.ui.settings

import android.net.Uri
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

/** The list of groups; each group is `settings/<group>` ([SettingsGroup.route]). */
const val SETTINGS_ROUTE = "settings"

/**
 * Settings' destinations: the list at [SETTINGS_ROUTE] and one screen per
 * [SettingsGroup]. Back from a group returns to the list.
 */
fun NavGraphBuilder.settingsGraph(nav: NavController, onImportFile: (Uri) -> Unit) {
    val back: () -> Unit = { nav.popBackStack() }
    composable(SETTINGS_ROUTE) {
        SettingsScreen(onBack = back, onOpenGroup = { nav.navigate(it.route) })
    }
    composable(SettingsGroup.ACCOUNT.route) { AccountScreen(onBack = back) }
    composable(SettingsGroup.DEVICES.route) {
        DevicesScreen(onBack = back, onOpenAccount = {
            nav.navigate(SettingsGroup.ACCOUNT.route) { popUpTo(SETTINGS_ROUTE) }
        })
    }
    composable(SettingsGroup.TRAINING.route) { TrainingScreen(onBack = back) }
    composable(SettingsGroup.ASSISTANT.route) { AssistantSettingsScreen(onBack = back) }
    composable(SettingsGroup.HEALTH_CONNECT.route) { HealthConnectSettingsScreen(onBack = back) }
    composable(SettingsGroup.DATA.route) { DataSettingsScreen(onBack = back, onImportFile = onImportFile) }
    composable(SettingsGroup.FEEDBACK.route) {
        FeedbackScreen(onBack = back, onOpenAccount = {
            nav.navigate(SettingsGroup.ACCOUNT.route) { popUpTo(SETTINGS_ROUTE) }
        })
    }
    composable(SettingsGroup.ABOUT.route) { AboutScreen(onBack = back) }
}
