package io.github.kanylbullen.tung.core.importing

import io.github.kanylbullen.tung.core.command.ExerciseCandidate
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.command.ExerciseResolver
import io.github.kanylbullen.tung.core.command.RefResolution
import io.github.kanylbullen.tung.core.command.normalizeExerciseName
import io.github.kanylbullen.tung.core.command.resolveRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A source app's exercise names mapped to Tung's catalogue
 * (`catalog/aliases/<source>.json`): each name to a core key (`back_squat`)
 * or an extended-catalogue id written `ext:<id>` (`ext:Dumbbell_Squat`).
 * Names are compared with [normalizeExerciseName], so case, punctuation and
 * a trailing space do not matter. Only exact equivalents belong here; a name
 * with none stays out and goes to the review.
 */
class ImportAliases(entries: Map<String, String>) {
    private val byName: Map<String, String> = entries.mapKeys { normalizeExerciseName(it.key) }

    val size: Int get() = byName.size

    /** The reference [name] is an alias for, or null. */
    fun refFor(name: String): ExerciseRef? = byName[normalizeExerciseName(name)]?.let(::aliasTarget)

    companion object {
        val EMPTY = ImportAliases(emptyMap())

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): ImportAliases = ImportAliases(json.decodeFromString(AliasFile.serializer(), text).aliases)

        /** `ext:<id>` is an extended-catalogue entry, anything else a core key. */
        fun aliasTarget(target: String): ExerciseRef =
            if (target.startsWith(EXTENDED_PREFIX)) ExerciseRef.Extended(target.removePrefix(EXTENDED_PREFIX)) else ExerciseRef.ByKey(target)

        const val EXTENDED_PREFIX = "ext:"
    }
}

/** The file format of `catalog/aliases/<source>.json`. */
@Serializable
data class AliasFile(val source: String, val aliases: Map<String, String>)

/** What one source name is in Tung. */
sealed interface ImportMatch {
    /** Found: through the alias table or an exact name. */
    data class Matched(val exercise: ExerciseCandidate, val viaAlias: Boolean) : ImportMatch

    /** Nothing exact: the person decides in the review, with [suggestions] (best first, possibly none). */
    data class Unmatched(val suggestions: List<ExerciseCandidate>) : ImportMatch
}

/**
 * Resolves each of [names]: the alias table first (a target that does not
 * resolve falls through), then core's exact-name rule ([resolveRef] with
 * [ExerciseRef.ByName], i.e. `matchExerciseName`), else [ImportMatch.Unmatched].
 * Never a guess.
 */
suspend fun resolveImportNames(
    names: Collection<String>,
    aliases: ImportAliases,
    resolver: ExerciseResolver,
): Map<String, ImportMatch> = names.associateWith { name ->
    val viaAlias = aliases.refFor(name)?.let { resolveRef(it, resolver) as? RefResolution.Resolved }
    if (viaAlias != null) {
        ImportMatch.Matched(viaAlias.exercise, viaAlias = true)
    } else {
        when (val byName = resolveRef(ExerciseRef.ByName(name), resolver)) {
            is RefResolution.Resolved -> ImportMatch.Matched(byName.exercise, viaAlias = false)
            is RefResolution.Unresolved -> ImportMatch.Unmatched(byName.question.suggestions)
        }
    }
}

/** What the person chose for a name in the review, or what resolution found. */
sealed interface ImportChoice {
    /** Log it as this exercise. */
    data class Use(val exercise: ExerciseCandidate) : ImportChoice

    /** Create a custom exercise with the source's name. */
    data object CreateCustom : ImportChoice

    /** Leave its sets and routine rows out. */
    data object Skip : ImportChoice
}

/** The id the custom exercise created for [name] gets: the same on a second import, so it is not made twice. */
fun importedCustomExerciseId(source: String, name: String): String = importId(source, "exercise", normalizeExerciseName(name))
