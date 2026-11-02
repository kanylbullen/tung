package io.github.kanylbullen.tung.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.github.kanylbullen.tung.BuildConfig
import io.github.kanylbullen.tung.TungApp
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.factoryOf
import kotlinx.coroutines.launch

/** The list of groups; each group is `settings/<group>` ([SettingsGroup.route]). */
const val SETTINGS_ROUTE = "settings"

/** The MIME types the system file picker offers: Jefit's export is a CSV, which phones label in several ways. */
val IMPORT_MIME_TYPES = arrayOf("text/*", "*/*")

/**
 * Settings' destinations: the list at [SETTINGS_ROUTE] and one screen per
 * [SettingsGroup]. Back from a group returns to the list. The screens are
 * the `ui` module's; what is the phone's (the ViewModels from the container,
 * the build, Health Connect, the pickers, Credential Manager) is wired here.
 */
fun NavGraphBuilder.settingsGraph(nav: NavController, onImportFile: (Uri) -> Unit) {
    val back: () -> Unit = { nav.popBackStack() }
    val openAccount: () -> Unit = { nav.navigate(SettingsGroup.ACCOUNT.route) { popUpTo(SETTINGS_ROUTE) } }
    composable(SETTINGS_ROUTE) {
        SettingsRoute(onBack = back, onOpenGroup = { nav.navigate(it.route) })
    }
    composable(SettingsGroup.ACCOUNT.route) { AccountRoute(onBack = back) }
    composable(SettingsGroup.DEVICES.route) {
        val container = appContainer()
        val vm: DevicesViewModel = viewModel(factory = factoryOf { DevicesViewModel(container.syncAccount, container.push) })
        DevicesScreen(vm, onBack = back, onOpenAccount = openAccount)
    }
    composable(SettingsGroup.TRAINING.route) {
        val container = appContainer()
        val vm: TrainingSettingsViewModel = viewModel(factory = factoryOf { TrainingSettingsViewModel(container.restTimer) })
        val equipment: EquipmentSectionViewModel = viewModel(factory = factoryOf { EquipmentSectionViewModel(container.equipmentSettings) })
        TrainingScreen(vm, equipment, onBack = back)
    }
    composable(SettingsGroup.ASSISTANT.route) {
        val container = appContainer()
        val vm: AiSettingsViewModel = viewModel(factory = factoryOf { AiSettingsViewModel(container.aiSettings, container.ai) })
        AssistantSettingsScreen(vm, onBack = back)
    }
    composable(SettingsGroup.HEALTH_CONNECT.route) { HealthConnectSettingsScreen(onBack = back) }
    composable(SettingsGroup.DATA.route) { DataRoute(onBack = back, onImportFile = onImportFile) }
    composable(SettingsGroup.FEEDBACK.route) { FeedbackRoute(onBack = back, onOpenAccount = openAccount) }
    composable(SettingsGroup.ABOUT.route) { AboutScreen(BuildConfig.VERSION_NAME, BuildConfig.BUILD_TYPE, onBack = back) }
}

/** The list of groups, with Health Connect's line from its own ViewModel. */
@Composable
private fun SettingsRoute(onBack: () -> Unit, onOpenGroup: (SettingsGroup) -> Unit) {
    val container = appContainer()
    val vm: SettingsViewModel = viewModel(
        factory = factoryOf { SettingsViewModel(container.syncAccount, container.push, container.restTimer, container.aiSettings, container.imports) },
    )
    SettingsScreen(
        vm,
        version = BuildConfig.VERSION_NAME,
        buildType = BuildConfig.BUILD_TYPE,
        healthSummary = healthConnectSummary(),
        onBack = onBack,
        onOpenGroup = onOpenGroup,
    )
}

/** Account, with Google's account sheet from Credential Manager over this activity. */
@Composable
private fun AccountRoute(onBack: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val app = context.applicationContext as TungApp
    val vm: AccountViewModel = viewModel(factory = factoryOf { AccountViewModel(container.syncAccount, app.defaultServerUrl) })
    val google = remember { app.createGoogleSignIn() }
    AccountScreen(vm, onBack = onBack, googleIdToken = { clientId -> google.idToken(context, clientId) })
}

/** Data, with the system file picker (`ACTION_OPEN_DOCUMENT`) behind "Import from Jefit". */
@Composable
private fun DataRoute(onBack: () -> Unit, onImportFile: (Uri) -> Unit) {
    val container = appContainer()
    val vm: ImportSectionViewModel = viewModel(
        factory = factoryOf { ImportSectionViewModel(container.imports, container.routineRepository, container.commands) },
    )
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportFile(uri)
    }
    DataSettingsScreen(vm, onBack = onBack, onPickFile = { picker.launch(IMPORT_MIME_TYPES) })
}

/** Feedback, with the photo picker; each picked image is read and scaled by [readScreenshot]. */
@Composable
private fun FeedbackRoute(onBack: () -> Unit, onOpenAccount: () -> Unit) {
    val container = appContainer()
    val vm: FeedbackViewModel = viewModel(
        factory = factoryOf { FeedbackViewModel(container.syncAccount, "android", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // As many as there is room for: the multiple picker needs at least two, so one left is the single picker.
    val room = MAX_FEEDBACK_SCREENSHOTS - state.form.screenshots.size
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(room.coerceAtLeast(2))) { uris ->
        if (uris.isNotEmpty()) scope.launch { vm.addScreenshots(uris.map { readScreenshot(context, it) }) }
    }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { vm.addScreenshots(listOf(readScreenshot(context, uri))) }
    }
    FeedbackScreen(vm, onBack = onBack, onOpenAccount = onOpenAccount, onPickScreenshots = { left ->
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        if (left >= 2) pickMany.launch(request) else pickOne.launch(request)
    })
}
