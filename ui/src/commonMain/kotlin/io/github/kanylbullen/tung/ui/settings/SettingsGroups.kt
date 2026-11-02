package io.github.kanylbullen.tung.ui.settings

import io.github.kanylbullen.tung.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.kanylbullen.tung.ui.theme.Tung

/** Where the source is: the About screen's licence row opens it. */
const val SOURCE_URL = "https://github.com/kanylbullen/tung"

/** Settings' Assistant: the BYOK section (CLAUDE.md, "In-app AI (BYOK)"). */
@Composable
fun AssistantSettingsScreen(vm: AiSettingsViewModel, onBack: () -> Unit) {
    SettingsPage(title = stringResource(Res.string.settings_group_assistant), onBack = onBack) { AiSection(vm) }
}

/**
 * Settings' Data: import from Jefit, undo the last import, delete its
 * routines (CLAUDE.md, "Import"). [onPickFile] opens the platform's file
 * picker; the chosen file goes to the import's preview from there.
 */
@Composable
fun DataSettingsScreen(vm: ImportSectionViewModel, onBack: () -> Unit, onPickFile: () -> Unit) {
    SettingsPage(title = stringResource(Res.string.settings_group_data), onBack = onBack) { ImportSection(vm, onPickFile) }
}

/**
 * Settings' About: the version and build type, the licence (AGPL-3.0, a tap
 * opens the source) and the open-data catalogue's credit. [version] and
 * [buildType] are the platform build's.
 */
@Composable
fun AboutScreen(version: String, buildType: String, onBack: () -> Unit) {
    val uris = LocalUriHandler.current
    SettingsPage(title = stringResource(Res.string.settings_group_about), onBack = onBack) {
        AboutItem(stringResource(Res.string.about_version), stringResource(Res.string.settings_summary_about, version, buildType))
        AboutItem(
            stringResource(Res.string.about_licence),
            stringResource(Res.string.about_licence_text),
            link = stringResource(Res.string.about_source, SOURCE_URL),
            onClick = { uris.openUri(SOURCE_URL) },
        )
        AboutItem(stringResource(Res.string.about_catalogue), stringResource(Res.string.about_catalogue_text))
    }
}

@Composable
private fun AboutItem(label: String, text: String, link: String? = null, onClick: (() -> Unit)? = null) {
    val base = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    Column(
        modifier = if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = Tung.colors.muted)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Tung.colors.text)
        link?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tung.colors.accentText) }
    }
}
