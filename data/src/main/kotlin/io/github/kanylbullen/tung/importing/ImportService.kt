package io.github.kanylbullen.tung.importing

import io.github.kanylbullen.tung.core.command.ExerciseResolver
import io.github.kanylbullen.tung.core.importing.ImportAliases
import io.github.kanylbullen.tung.core.importing.ImportBundle
import io.github.kanylbullen.tung.core.importing.ImportChoice
import io.github.kanylbullen.tung.core.importing.ImportMatch
import io.github.kanylbullen.tung.core.importing.adapterFor
import io.github.kanylbullen.tung.core.importing.importExerciseNames
import io.github.kanylbullen.tung.core.importing.resolveImportNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** A parsed export with what each of its exercise names is in Tung, for the preview. */
data class ImportPreview(val bundle: ImportBundle, val matches: Map<String, ImportMatch>) {
    /** The names the person has to decide on, in the order the export uses them. */
    val unmatched: List<String> get() = matches.filterValues { it is ImportMatch.Unmatched }.keys.toList()

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
    private val zone: () -> ZoneId,
) {
    suspend fun read(text: String): ImportRead = withContext(Dispatchers.Default) {
        val adapter = adapterFor(text) ?: return@withContext ImportRead.NotRecognised
        val bundle = adapter.parse(text, zone())
        val matches = resolveImportNames(bundle.importExerciseNames(), aliases(adapter.source), resolver)
        ImportRead.Ready(ImportPreview(bundle, matches))
    }

    suspend fun apply(bundle: ImportBundle, choices: Map<String, ImportChoice>): ImportResult = importer.apply(bundle, choices)

    suspend fun undo(result: ImportResult): Int = importer.undo(result.batchId, result.routineIds)
}
