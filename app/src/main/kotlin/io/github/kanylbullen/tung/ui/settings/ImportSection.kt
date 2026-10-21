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
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandHandler
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.importing.ImportService
import io.github.kanylbullen.tung.importing.LastImport
import io.github.kanylbullen.tung.ui.common.SecondaryButton
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.factoryOf
import io.github.kanylbullen.tung.ui.common.localDate
import io.github.kanylbullen.tung.ui.routines.DeleteRoutinesConfirm
import io.github.kanylbullen.tung.ui.routines.DeleteRoutinesDialog
import io.github.kanylbullen.tung.ui.theme.Tung
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The MIME types the system file picker offers: Jefit's export is a CSV, which phones label in several ways. */
val IMPORT_MIME_TYPES = arrayOf("text/*", "*/*")

/** The last import, undoing it, and deleting the routines it created, for Settings. */
class ImportSectionViewModel(
    private val imports: ImportService,
    private val routines: RoutineRepository,
    private val commands: CommandHandler,
) : ViewModel() {
    val last: StateFlow<LastImport?> = imports.lastImport.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The routines the last import created that still exist, in the import's order; empty without one. */
    val importedRoutines: StateFlow<List<String>> = combine(imports.lastImport, routines.observeRoutines()) { last, list ->
        val existing = list.map { it.id }.toSet()
        last?.routineIds.orEmpty().filter { it in existing }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _confirm = MutableStateFlow<DeleteRoutinesConfirm?>(null)

    /** Non-null while "Delete routines from the last import" asks. */
    val confirm: StateFlow<DeleteRoutinesConfirm?> = _confirm.asStateFlow()

    fun undoLast() {
        val last = last.value ?: return
        viewModelScope.launch { imports.undo(last) }
    }

    fun askDeleteRoutines() {
        val ids = importedRoutines.value
        if (ids.isEmpty()) return
        viewModelScope.launch { _confirm.value = DeleteRoutinesConfirm(ids, routines.programDaysUsing(ids)) }
    }

    fun dismissDeleteRoutines() {
        _confirm.value = null
    }

    /** The import's routines through `delete_routines`; its workouts stay, and undo still removes them. */
    fun confirmDeleteRoutines() {
        val confirm = _confirm.value ?: return
        _confirm.value = null
        viewModelScope.launch { commands.execute(Command.DeleteRoutines(confirm.ids)) }
    }
}

/**
 * Settings' "Import" section (CLAUDE.md, "Import"): "Import from Jefit" opens
 * the system file picker (`ACTION_OPEN_DOCUMENT`), and the chosen file goes
 * to the import's preview ([onImportFile]). The last import can be undone
 * here too, after its summary is gone, and while routines it created still
 * exist, "Delete routines from the last import (N)" removes just those
 * (issue #79). One call site, [DataSettingsScreen].
 */
@Composable
fun ImportSection(onImportFile: (Uri) -> Unit) {
    val container = appContainer()
    val vm: ImportSectionViewModel = viewModel(
        factory = factoryOf { ImportSectionViewModel(container.imports, container.routineRepository, container.commands) },
    )
    val last by vm.last.collectAsStateWithLifecycle()
    val importedRoutines by vm.importedRoutines.collectAsStateWithLifecycle()
    val confirm by vm.confirm.collectAsStateWithLifecycle()
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
        if (importedRoutines.isNotEmpty()) {
            SecondaryButton(
                text = stringResource(R.string.import_delete_routines, importedRoutines.size),
                onClick = vm::askDeleteRoutines,
            )
        }
    }
    confirm?.let { asked ->
        DeleteRoutinesDialog(
            count = asked.ids.size,
            programDays = asked.programDays,
            body = stringResource(R.string.import_delete_routines_body),
            onDismiss = vm::dismissDeleteRoutines,
            onConfirm = vm::confirmDeleteRoutines,
        )
    }
}
