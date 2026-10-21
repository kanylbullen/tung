package io.github.kanylbullen.tung.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.importing.ImportService
import io.github.kanylbullen.tung.importing.LastImport
import io.github.kanylbullen.tung.ui.common.SecondaryButton
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.factoryOf
import io.github.kanylbullen.tung.ui.common.localDate
import io.github.kanylbullen.tung.ui.theme.Tung
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The MIME types the system file picker offers: Jefit's export is a CSV, which phones label in several ways. */
val IMPORT_MIME_TYPES = arrayOf("text/*", "*/*")

/** The last import, and undoing it, for Settings. */
class ImportSectionViewModel(private val imports: ImportService) : ViewModel() {
    val last: StateFlow<LastImport?> = imports.lastImport.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun undoLast() {
        val last = last.value ?: return
        viewModelScope.launch { imports.undo(last) }
    }
}

/**
 * Settings' "Import" section (CLAUDE.md, "Import"): "Import from Jefit" opens
 * the system file picker (`ACTION_OPEN_DOCUMENT`), and the chosen file goes
 * to the import's preview ([onImportFile]). The last import can be undone
 * here too, after its summary is gone. One call site in [SettingsScreen].
 */
@Composable
fun ImportSection(onImportFile: (Uri) -> Unit) {
    val container = appContainer()
    val vm: ImportSectionViewModel = viewModel(factory = factoryOf { ImportSectionViewModel(container.imports) })
    val last by vm.last.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportFile(uri)
    }
    Text(stringResource(R.string.import_section), style = MaterialTheme.typography.titleMedium, color = Tung.colors.muted)
    Text(stringResource(R.string.import_jefit_hint), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
    SecondaryButton(text = stringResource(R.string.import_jefit), onClick = { picker.launch(IMPORT_MIME_TYPES) })
    last?.let { import ->
        Text(
            pluralStringResource(R.plurals.import_last, import.workouts, import.workouts, localDate(import.at)),
            style = MaterialTheme.typography.bodyMedium,
            color = Tung.colors.muted,
        )
        SecondaryButton(text = stringResource(R.string.import_undo_last), onClick = vm::undoLast)
    }
}
