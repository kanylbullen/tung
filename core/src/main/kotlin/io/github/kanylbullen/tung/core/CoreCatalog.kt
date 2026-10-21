package io.github.kanylbullen.tung.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One entry in `catalog/core.json`, the curated core catalogue. [key] is the
 * stable key that carries the exercise's id ([catalogExerciseId]); [muscleGroup]
 * and [equipment] are [MuscleGroup] and [Equipment] keys, and the muscle lists
 * hold [Muscle] keys (1–3 primary, 0–4 secondary; [MuscleIndex] reads them).
 * [instructions] are the steps per language tag, copied from the entry's
 * free-exercise-db twin by `catalog/tools/import_free_exercise_db.py`; only
 * `en` exists today, and an entry without a twin has none.
 */
@Serializable
data class CoreExercise(
    val key: String,
    /** Language tag → name. `en` and `sv` are required by the catalogue's rules, which the tests check, not the parser. */
    val name: Map<String, String>,
    val muscleGroup: String,
    val equipment: String,
    val primaryMuscles: List<String> = emptyList(),
    val secondaryMuscles: List<String> = emptyList(),
    /** Language tag → the steps, in order. Optional: absent for an entry without a free-exercise-db twin. */
    val instructions: Map<String, List<String>> = emptyMap(),
) {
    /** The steps in [language], falling back to English; empty when the entry has none. */
    fun instructionsIn(language: String): List<String> =
        instructions[language]?.takeIf { it.isNotEmpty() } ?: instructions["en"].orEmpty()
}

/** Parsing of `catalog/core.json`. The caller reads the file (an asset, a resource, a path) and hands over the text. */
object CoreCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<CoreExercise> = json.decodeFromString(text)
}
