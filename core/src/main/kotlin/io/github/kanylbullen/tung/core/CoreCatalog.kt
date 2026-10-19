package io.github.kanylbullen.tung.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One entry in `catalog/core.json`, the curated core catalogue. [key] is the
 * stable key that carries the exercise's id ([catalogExerciseId]); [muscleGroup]
 * and [equipment] are [MuscleGroup] and [Equipment] keys.
 */
@Serializable
data class CoreExercise(
    val key: String,
    /** Language tag → name. `en` and `sv` are required by the catalogue's rules, which the tests check, not the parser. */
    val name: Map<String, String>,
    val muscleGroup: String,
    val equipment: String,
)

/** Parsing of `catalog/core.json`. The caller reads the file (an asset, a resource, a path) and hands over the text. */
object CoreCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<CoreExercise> = json.decodeFromString(text)
}
