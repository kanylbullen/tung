package io.github.kanylbullen.tung.web.importing

import io.github.kanylbullen.tung.core.command.ExerciseCandidate
import io.github.kanylbullen.tung.core.command.ExerciseResolver
import io.github.kanylbullen.tung.core.importing.ImportAliases
import io.github.kanylbullen.tung.core.importing.ImportBundle
import io.github.kanylbullen.tung.core.importing.ImportChoice
import io.github.kanylbullen.tung.core.importing.ImportMatch
import io.github.kanylbullen.tung.core.importing.NameUsage
import io.github.kanylbullen.tung.core.importing.adapterFor
import io.github.kanylbullen.tung.core.importing.importExerciseNames
import io.github.kanylbullen.tung.core.importing.nameUsage
import io.github.kanylbullen.tung.core.importing.resolveImportNames
import kotlinx.browser.window
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A parsed export with what each of its exercise names is in Tung, the phone's `ImportPreview`. */
data class ImportPreview(val bundle: ImportBundle, val matches: Map<String, ImportMatch>) {
    /** The names the person has to decide on, in the order the export uses them. */
    val unmatched: List<String> get() = matches.filterValues { it is ImportMatch.Unmatched }.keys.toList()

    /** The offline suggestions for each unmatched name, best first. */
    val suggestions: Map<String, List<ExerciseCandidate>> get() =
        matches.mapNotNull { (name, match) -> (match as? ImportMatch.Unmatched)?.let { name to it.suggestions } }.toMap()

    /** How much of the export uses each name, so the review shows which names carry history. */
    val usage: Map<String, NameUsage> by lazy { bundle.nameUsage() }

    /** What resolution found, as the choices the import starts from. */
    val matchedChoices: Map<String, ImportChoice> get() =
        matches.mapNotNull { (name, match) -> (match as? ImportMatch.Matched)?.let { name to ImportChoice.Use(it.exercise) } }.toMap()
}

/**
 * Parses [text] with the adapter that recognises it and resolves its names
 * (the source's alias table, then exact names), as the phone's
 * `ImportService.read`; null when no adapter recognises the file.
 */
suspend fun readImport(
    text: String,
    zone: TimeZone,
    aliases: suspend (source: String) -> ImportAliases,
    resolver: ExerciseResolver,
): ImportPreview? {
    val adapter = adapterFor(text) ?: return null
    val bundle = adapter.parse(text, zone)
    return ImportPreview(bundle, resolveImportNames(bundle.importExerciseNames(), aliases(adapter.source), resolver))
}

/** The last import in this browser, for "Undo last import" and "Delete routines from the last import". */
@Serializable
data class LastImport(val source: String, val batchId: String, val routineIds: List<String>, val workouts: Int, val at: Long)

/**
 * The last import, kept in this browser's localStorage (like the phone's
 * `ImportHistory` in DataStore: per device, not synced). Reads and writes
 * that fail (a private window) keep nothing.
 */
object ImportMemory {
    private const val KEY = "tung-last-import"
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): LastImport? = try {
        window.localStorage.getItem(KEY)?.let { json.decodeFromString(LastImport.serializer(), it) }
    } catch (e: Throwable) {
        null
    }

    fun save(last: LastImport) {
        try {
            window.localStorage.setItem(KEY, json.encodeToString(LastImport.serializer(), last))
        } catch (e: Throwable) {
            // Not remembered: the import itself stands.
        }
    }

    fun forget() {
        try {
            window.localStorage.removeItem(KEY)
        } catch (e: Throwable) {
            // Nothing to forget.
        }
    }
}
