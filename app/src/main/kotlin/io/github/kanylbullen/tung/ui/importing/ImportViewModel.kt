package io.github.kanylbullen.tung.ui.importing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.importing.ImportChoice
import io.github.kanylbullen.tung.importing.ImportPreview
import io.github.kanylbullen.tung.importing.ImportRead
import io.github.kanylbullen.tung.importing.ImportResult
import io.github.kanylbullen.tung.importing.ImportService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ImportPhase { Loading, NotRecognised, Unreadable, Empty, Preview, Importing, Done, Undoing, Undone }

data class ImportUiState(
    val phase: ImportPhase = ImportPhase.Loading,
    val preview: ImportPreview? = null,
    /** The review's decisions, for the names in [ImportPreview.unmatched] only. */
    val choices: Map<String, ImportChoice> = emptyMap(),
    val result: ImportResult? = null,
    val removed: Int = 0,
) {
    val undecided: List<String> get() = preview?.unmatched.orEmpty().filter { it !in choices }
    val canImport: Boolean get() = phase == ImportPhase.Preview && undecided.isEmpty()
}

/**
 * The import from a file: read and parse it ([ImportService.read], off the
 * main thread), show the preview with the review of unmatched names, then
 * import and offer to undo. [readFile] gives the file's text, or null when it
 * cannot be read; the screen reads it through the content resolver.
 */
class ImportViewModel(
    private val imports: ImportService,
    private val readFile: suspend () -> String?,
) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    /** The name a "Choose" opened the picker for; the picker's answer goes to it. */
    private var pickingFor: String? = null

    init {
        viewModelScope.launch {
            val text = runCatching { readFile() }.getOrNull()
            if (text == null) {
                _state.update { it.copy(phase = ImportPhase.Unreadable) }
                return@launch
            }
            when (val read = imports.read(text)) {
                ImportRead.NotRecognised -> _state.update { it.copy(phase = ImportPhase.NotRecognised) }
                is ImportRead.Ready -> {
                    val empty = read.preview.bundle.workouts.isEmpty() && read.preview.bundle.routines.isEmpty()
                    _state.update { it.copy(phase = if (empty) ImportPhase.Empty else ImportPhase.Preview, preview = read.preview) }
                }
            }
        }
    }

    fun choose(name: String, choice: ImportChoice) {
        _state.update { it.copy(choices = it.choices + (name to choice)) }
    }

    /** "Choose" in the review: remember the name; the picker's answer comes to [onPicked]. */
    fun startPicking(name: String) {
        pickingFor = name
    }

    fun onPicked(ref: ExerciseRef) {
        val name = pickingFor ?: return
        pickingFor = null
        viewModelScope.launch {
            imports.candidate(ref)?.let { choose(name, ImportChoice.Use(it)) }
        }
    }

    fun import() {
        val current = _state.value
        val preview = current.preview ?: return
        if (!current.canImport) return
        _state.update { it.copy(phase = ImportPhase.Importing) }
        viewModelScope.launch {
            val result = imports.apply(preview.bundle, preview.matchedChoices + current.choices)
            _state.update { it.copy(phase = ImportPhase.Done, result = result) }
        }
    }

    fun undo() {
        val result = _state.value.result ?: return
        if (_state.value.phase != ImportPhase.Done) return
        _state.update { it.copy(phase = ImportPhase.Undoing) }
        viewModelScope.launch {
            val removed = imports.undo(result)
            _state.update { it.copy(phase = ImportPhase.Undone, removed = removed) }
        }
    }
}
