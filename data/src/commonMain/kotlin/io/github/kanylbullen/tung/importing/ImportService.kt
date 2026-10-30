package io.github.kanylbullen.tung.importing

import io.github.kanylbullen.tung.core.command.ExerciseCandidate
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.command.ExerciseResolver
import io.github.kanylbullen.tung.core.command.RefResolution
import io.github.kanylbullen.tung.core.command.resolveRef
import io.github.kanylbullen.tung.core.importing.ImportAliases
import io.github.kanylbullen.tung.core.importing.ImportBundle
import io.github.kanylbullen.tung.core.importing.ImportChoice
import io.github.kanylbullen.tung.core.importing.ImportMatch
import io.github.kanylbullen.tung.core.importing.NameUsage
import io.github.kanylbullen.tung.core.importing.adapterFor
import io.github.kanylbullen.tung.core.importing.importExerciseNames
import io.github.kanylbullen.tung.core.importing.nameUsage
import io.github.kanylbullen.tung.core.importing.resolveImportNames
import io.github.kanylbullen.tung.data.systemMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone

/** A parsed export with what each of its exercise names is in Tung, for the preview. */
data class ImportPreview(val bundle: ImportBundle, val matches: Map<String, ImportMatch>) {
    /** The names the person has to decide on, in the order the export uses them. */
    val unmatched: List<String> get() = matches.filterValues { it is ImportMatch.Unmatched }.keys.toList()

    /** The offline suggestions for each unmatched name, best first (core's `importSuggestions`). */
    val suggestions: Map<String, List<ExerciseCandidate>> get() =
        matches.mapNotNull { (name, match) -> (match as? ImportMatch.Unmatched)?.let { name to it.suggestions } }.toMap()

    /** How much of the export uses each name: workouts, sets, routines; so the review shows which names carry history. */
    val usage: Map<String, NameUsage> by lazy { bundle.nameUsage() }

    /** What resolution found, as the choices the import starts from. */
    val matchedChoices: Map<String, ImportChoice> get() =
        matches.mapNotNull { (name, match) -> (match as? ImportMatch.Matched)?.let { name to ImportChoice.Use(it.exercise) } }.toMap()
}

/** The outcome of [ImportService.read]. */
sealed interface ImportRead {
    data class Ready(val preview: ImportPreview) : ImportRead

    /** No adapter recognises the file. */
    data object NotRecognised : ImportRead
}

/**
 * Import from another app's export, for the phone's Settings: [read] parses
 * the file's text with the adapter that recognises it (core, off the main
 * thread) and resolves its names (alias table, then exact names); [apply] and
 * [undo] are the [Importer]'s.
 */
class ImportService(
    private val importer: Importer,
    private val resolver: ExerciseResolver,
    /** The alias table of a source, `catalog/aliases/<source>.json`; empty when there is none. */
    private val aliases: suspend (source: String) -> ImportAliases,
    private val zone: () -> TimeZone,
    /** Remembers the last import for "Undo last import" in Settings; null keeps nothing (tests). */
    private val history: ImportHistory? = null,
    private val clock: () -> Long = ::systemMillis,
) {

    /** The last import on this device, until it is undone. */
    val lastImport: Flow<LastImport?> = history?.last ?: flowOf(null)

    suspend fun read(text: String): ImportRead = withContext(Dispatchers.Default) {
        val adapter = adapterFor(text) ?: return@withContext ImportRead.NotRecognised
        val bundle = adapter.parse(text, zone())
        val matches = resolveImportNames(bundle.importExerciseNames(), aliases(adapter.source), resolver)
        ImportRead.Ready(ImportPreview(bundle, matches))
    }

    suspend fun apply(bundle: ImportBundle, choices: Map<String, ImportChoice>): ImportResult {
        val result = importer.apply(bundle, choices)
        if (result.workoutsAdded > 0 || result.routineIds.isNotEmpty()) history?.remember(bundle.source, result, clock())
        return result
    }

    /** Undoes [result]'s import; returns how many workouts it removed. */
    suspend fun undo(result: ImportResult): Int = undo(result.batchId, result.routineIds)

    /** Undoes [last] (from [lastImport]). */
    suspend fun undo(last: LastImport): Int = undo(last.batchId, last.routineIds)

    private suspend fun undo(batchId: String, routineIds: List<String>): Int {
        val removed = importer.undo(batchId, routineIds)
        history?.forget(batchId)
        return removed
    }

    /** The exercise a picker's choice names (a row, or an extended entry not picked yet), for the review; null if gone. */
    suspend fun candidate(ref: ExerciseRef): ExerciseCandidate? = (resolveRef(ref, resolver) as? RefResolution.Resolved)?.exercise
}
