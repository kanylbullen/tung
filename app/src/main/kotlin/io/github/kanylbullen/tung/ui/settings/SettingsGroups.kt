package io.github.kanylbullen.tung.ui.settings

import android.net.Uri
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.kanylbullen.tung.BuildConfig
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.ui.theme.Tung

/** Where the source is: the About screen's licence row opens it. */
const val SOURCE_URL = "https://github.com/kanylbullen/tung"

/** Settings' Assistant: the BYOK section (CLAUDE.md, "In-app AI (BYOK)"). */
@Composable
fun AssistantSettingsScreen(onBack: () -> Unit) {
    SettingsPage(title = stringResource(R.string.settings_group_assistant), onBack = onBack) { AiSection() }
}

/** Settings' Health Connect: its two opt-ins (CLAUDE.md, "Health Connect"). */
@Composable
fun HealthConnectSettingsScreen(onBack: () -> Unit) {
    SettingsPage(title = stringResource(R.string.settings_group_health), onBack = onBack) { HealthConnectSection() }
}

/** Settings' Data: import from Jefit and undo the last import (CLAUDE.md, "Import"). */
@Composable
fun DataSettingsScreen(onBack: () -> Unit, onImportFile: (Uri) -> Unit) {
    SettingsPage(title = stringResource(R.string.settings_group_data), onBack = onBack) { ImportSection(onImportFile) }
}

/**
 * Settings' About: the version and build type, the licence (AGPL-3.0, a tap
 * opens the source) and the open-data catalogue's credit.
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val uris = LocalUriHandler.current
    SettingsPage(title = stringResource(R.string.settings_group_about), onBack = onBack) {
        AboutItem(stringResource(R.string.about_version), stringResource(R.string.settings_summary_about, BuildConfig.VERSION_NAME, BuildConfig.BUILD_TYPE))
        AboutItem(
            stringResource(R.string.about_licence),
            stringResource(R.string.about_licence_text),
            link = stringResource(R.string.about_source, SOURCE_URL),
            onClick = { uris.openUri(SOURCE_URL) },
        )
        AboutItem(stringResource(R.string.about_catalogue), stringResource(R.string.about_catalogue_text))
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
