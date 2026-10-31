package io.github.kanylbullen.tung.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.kanylbullen.tung.core.importing.ImportAliases
import io.github.kanylbullen.tung.core.importing.ImportChoice
import io.github.kanylbullen.tung.web.WebContainer
import io.github.kanylbullen.tung.web.importing.ImportMemory
import io.github.kanylbullen.tung.web.importing.ImportPreview
import io.github.kanylbullen.tung.web.importing.ImportResult
import io.github.kanylbullen.tung.web.importing.LastImport
import io.github.kanylbullen.tung.web.importing.readImport
import io.github.kanylbullen.tung.web.importing.undoImport
import kotlin.js.Date
import kotlin.js.Promise
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul

/** The import's file is read whole; a Jefit export is a few megabytes. */
private const val MAX_IMPORT_BYTES = 64.0 * 1024 * 1024

private sealed interface ImportPhase {
    data object Choose : ImportPhase
    data object Reading : ImportPhase
    data class Failed(val message: String) : ImportPhase
    data class Review(val preview: ImportPreview) : ImportPhase
    data object Importing : ImportPhase
    data class Done(val result: ImportResult) : ImportPhase
}

/**
 * Import from Jefit (`#/import`), the phone's import: choose the export's
 * CSV, core's adapter reads it in the browser, the preview counts it, every
 * name without an exact match is decided (Choose, Create, Skip, or one of
 * its offline suggestions; a decided row collapses until Change), and
 * Import writes it in one go. The summary offers Undo import.
 */
@Composable
fun ImportScreen(container: WebContainer) {
    val strings = container.strings
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<ImportPhase>(ImportPhase.Choose) }
    var choices by remember { mutableStateOf<Map<String, ImportChoice>>(emptyMap()) }
    var reopened by remember { mutableStateOf<Set<String>>(emptySet()) }
    var choosingFor by remember { mutableStateOf<String?>(null) }
    var undone by remember { mutableStateOf<Int?>(null) }

    fun decide(name: String, choice: ImportChoice) {
        choices = choices + (name to choice)
        reopened = reopened - name
    }

    fun read(file: dynamic) {
        if ((file.size as Double) > MAX_IMPORT_BYTES) {
            phase = ImportPhase.Failed(strings.importUnreadable)
            return
        }
        phase = ImportPhase.Reading
        scope.launch {
            phase = try {
                val text = (file.text() as Promise<String>).await()
                val preview = readImport(text, TimeZone.currentSystemDefault(), { source -> aliases(source) }, container.resolver)
                when {
                    preview == null -> ImportPhase.Failed(strings.importNotRecognised)
                    preview.bundle.workouts.isEmpty() && preview.bundle.routines.isEmpty() -> ImportPhase.Failed(strings.importEmpty)
                    else -> {
                        choices = emptyMap()
                        reopened = emptySet()
                        ImportPhase.Review(preview)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                console.warn("The import could not be read", e)
                ImportPhase.Failed(strings.importUnreadable)
            }
        }
    }

    Div({ classes("screen", "with-actions") }) {
        Div({ classes("topbar", "topbar-start") }) {
            A(href = Route.SETTINGS.hash, attrs = { classes("text-button"); attr("aria-label", strings.settings) }) { Text("←") }
            H1({ classes("detail-title") }) { Text(strings.importJefit) }
        }
        when (val current = phase) {
            ImportPhase.Choose, is ImportPhase.Failed -> {
                P({ classes("hint") }) { Text(strings.importJefitHint) }
                if (current is ImportPhase.Failed) P({ classes("error"); attr("role", "alert") }) { Text(current.message) }
                Label(attrs = { classes("file-button") }) {
                    Text(strings.importJefit)
                    Input(InputType.File) {
                        attr("accept", ".csv,text/csv,text/plain")
                        onChange { event ->
                            val file: dynamic = event.target.asDynamic().files?.item(0)
                            if (file != null) read(file)
                        }
                    }
                }
            }
            ImportPhase.Reading -> P({ classes("hint"); attr("role", "status") }) { Text(strings.importReading) }
            ImportPhase.Importing -> P({ classes("hint"); attr("role", "status") }) { Text(strings.importing) }
            is ImportPhase.Review -> Review(container, current.preview, choices, reopened, onDecide = ::decide, onReopen = { reopened = reopened + it }, onChoose = { choosingFor = it })
            is ImportPhase.Done -> {
                val result = current.result
                H2({ classes("section") }) { Text(strings.importDoneTitle) }
                P({ classes("hint") }) {
                    Text(strings.importDoneAdded(strings.importCounts(result.workoutsAdded, result.setsAdded, result.routineIds.size)))
                }
                if (result.workoutsAlreadyThere > 0) P({ classes("hint") }) { Text(strings.importDoneAlready(result.workoutsAlreadyThere)) }
                undone?.let { P({ classes("hint", "accent"); attr("role", "status") }) { Text(strings.importUndone(it)) } }
            }
        }
    }

    when (val current = phase) {
        is ImportPhase.Review -> {
            val names = current.preview.unmatched
            val ready = names.all { it in choices }
            Div({ classes("bottom-actions") }) {
                Button({
                    classes("accent-button")
                    if (!ready) disabled()
                    onClick {
                        val preview = current.preview
                        phase = ImportPhase.Importing
                        scope.launch {
                            val result = container.importer.apply(preview.bundle, preview.matchedChoices + choices)
                            if (result.workoutsAdded > 0 || result.routineIds.isNotEmpty()) {
                                ImportMemory.save(LastImport(preview.bundle.source, result.batchId, result.routineIds, result.workoutsAdded, Date.now().toLong()))
                            }
                            container.syncInBackground()
                            phase = ImportPhase.Done(result)
                        }
                    }
                }) { Text(strings.importAction) }
            }
        }
        is ImportPhase.Done -> Div({ classes("bottom-actions") }) {
            if (undone == null && (current.result.workoutsAdded > 0 || current.result.routineIds.isNotEmpty())) {
                Button({
                    classes("secondary")
                    onClick {
                        scope.launch {
                            undone = undoImport(container.store, container.commands, current.result.batchId, current.result.routineIds)
                            ImportMemory.forget()
                        }
                    }
                }) { Text(strings.undoImport) }
            }
            Button({ classes("accent-button"); onClick { go(Route.SETTINGS) } }) { Text(strings.done) }
        }
        else -> Unit
    }

    choosingFor?.let { name ->
        Picker(container, onClose = { choosingFor = null }) { exerciseId ->
            choosingFor = null
            scope.launch { container.resolver.lookupId(exerciseId)?.let { decide(name, ImportChoice.Use(it)) } }
        }
    }
}

/** The alias table of [source], shipped as `catalog/aliases/<source>.json`; empty when it cannot be read. */
private suspend fun aliases(source: String): ImportAliases = try {
    ImportAliases.parse(window.fetch("catalog/aliases/$source.json").await().text().await())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    ImportAliases.EMPTY
}

@Composable
private fun Review(
    container: WebContainer,
    preview: ImportPreview,
    choices: Map<String, ImportChoice>,
    reopened: Set<String>,
    onDecide: (String, ImportChoice) -> Unit,
    onReopen: (String) -> Unit,
    onChoose: (String) -> Unit,
) {
    val strings = container.strings
    val locale = container.locale
    val bundle = preview.bundle
    bundle.dateRange?.let { range ->
        P({ classes("detail-date") }) { Text(longDate(range.first, locale) + " – " + longDate(range.last, locale)) }
    }
    P({ classes("hint") }) { Text(strings.importCounts(bundle.workouts.size, bundle.setCount, bundle.routines.size)) }
    bundle.setTypes.forEach { (type, count) -> P({ classes("hint") }) { Text(strings.setTypeNote(type, count)) } }
    if (bundle.skipped.isNotEmpty()) {
        H2({ classes("section") }) { Text(strings.skippedTitle) }
        Ul({ classes("plain-list") }) { bundle.skipped.forEach { (reason, count) -> Li { Text(strings.skipReason(reason, count)) } } }
    }

    val names = preview.unmatched
    if (names.isEmpty()) return
    H2({ classes("section") }) { Text(strings.reviewTitle) }
    P({ classes("hint") }) { Text(strings.reviewHint) }
    P({ classes("hint", "numbers"); attr("role", "status") }) { Text(strings.decidedCount(names.count { it in choices }, names.size)) }
    Div({ classes("action-row") }) {
        Button({ classes("secondary"); onClick { names.filter { it !in choices }.forEach { onDecide(it, ImportChoice.CreateCustom) } } }) { Text(strings.createRemaining) }
        Button({ classes("secondary"); onClick { names.filter { it !in choices }.forEach { onDecide(it, ImportChoice.Skip) } } }) { Text(strings.skipRemaining) }
    }
    Ul({ classes("import-rows") }) {
        names.forEach { name ->
            key(name) {
                val choice = choices[name]
                val usage = preview.usage[name]
                val usageLine = if (usage != null && usage.workouts > 0) strings.usedInWorkouts(usage.workouts) else strings.onlyInRoutines
                Li({ classes("import-row") }) {
                    Div({ classes("title") }) { Span({ classes("plain") }) { Text(name) } }
                    Div({ classes("details") }) { Text(usageLine) }
                    if (choice != null && name !in reopened) {
                        Div({ classes("decided") }) {
                            Span({ classes("accent") }) {
                                Text(
                                    when (choice) {
                                        is ImportChoice.Use -> strings.decidedUse(choice.exercise.displayName)
                                        ImportChoice.CreateCustom -> strings.decidedCreate(name)
                                        ImportChoice.Skip -> strings.decidedSkip
                                    },
                                )
                            }
                            Button({ classes("text-button"); attr("aria-label", strings.change + " " + name); onClick { onReopen(name) } }) { Text(strings.change) }
                        }
                    } else {
                        val suggestions = preview.suggestions[name].orEmpty().take(2)
                        if (suggestions.isNotEmpty()) {
                            Div({ classes("chips", "flush", "wrap") }) {
                                suggestions.forEach { candidate ->
                                    Button({
                                        classes("chip")
                                        if ((choice as? ImportChoice.Use)?.exercise?.id == candidate.id) classes("on")
                                        onClick { onDecide(name, ImportChoice.Use(candidate)) }
                                    }) { Text(candidate.displayName + "?") }
                                }
                            }
                        }
                        Div({ classes("action-row", "flush") }) {
                            Button({ classes("secondary"); onClick { onChoose(name) } }) { Text(strings.choose) }
                            Button({ classes("secondary"); onClick { onDecide(name, ImportChoice.CreateCustom) } }) { Text(strings.createChoice) }
                            Button({ classes("secondary"); onClick { onDecide(name, ImportChoice.Skip) } }) { Text(strings.skip) }
                        }
                    }
                }
            }
        }
    }
}
