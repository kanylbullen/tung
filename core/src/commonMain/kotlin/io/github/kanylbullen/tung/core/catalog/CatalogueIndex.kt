package io.github.kanylbullen.tung.core.catalog

import io.github.kanylbullen.tung.core.CoreExercise
import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.ExerciseMuscles
import io.github.kanylbullen.tung.core.ExtendedExercise
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.MuscleIndex
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.extendedExerciseId

/**
 * What the catalogue files say about one exercise, for its page: the English
 * name (the fallback; core exercises are shown by their `ex_<key>` string),
 * group, equipment, muscles and instructions. Exactly one of [key] (core) and
 * [fedId] (extended) is set.
 */
data class CatalogueEntry(
    val key: String?,
    val fedId: String?,
    val englishName: String,
    val muscleGroup: MuscleGroup,
    val equipment: Equipment,
    val muscles: ExerciseMuscles?,
    /** Language tag → steps. */
    val instructions: Map<String, List<String>>,
) {
    /** The steps in [language], falling back to English; empty when the catalogue has none. */
    fun instructionsIn(language: String): List<String> =
        instructions[language]?.takeIf { it.isNotEmpty() } ?: instructions["en"].orEmpty()
}

/**
 * Both catalogue files by exercise id: [catalogExerciseId] for a core entry,
 * [extendedExerciseId] for an extended one, so a Room row finds its entry by
 * its id. Custom exercises are not in it. Built from what the caller read
 * (`core.json`, `extended.json`); the muscles come from [MuscleIndex], so
 * both agree.
 */
class CatalogueIndex private constructor(
    private val byId: Map<String, CatalogueEntry>,
) {
    /** The entry of the exercise whose Room id is [exerciseId]; null for a custom exercise. */
    fun byExerciseId(exerciseId: String): CatalogueEntry? = byId[exerciseId]

    /** The extended entry with free-exercise-db id [fedId]. */
    fun byFedId(fedId: String): CatalogueEntry? = byId[extendedExerciseId(fedId)]

    /** The entry a page shows for [ref]. */
    fun of(ref: ExercisePageRef): CatalogueEntry? = when (ref) {
        is ExercisePageRef.Library -> byExerciseId(ref.exerciseId)
        is ExercisePageRef.Extended -> byFedId(ref.fedId)
    }

    val size: Int get() = byId.size

    companion object {
        val EMPTY = CatalogueIndex(emptyMap())

        fun build(core: List<CoreExercise>, extended: List<ExtendedExercise>): CatalogueIndex {
            val muscles = MuscleIndex.build(core, extended)
            val map = HashMap<String, CatalogueEntry>()
            for (e in extended) {
                val id = extendedExerciseId(e.id)
                map[id] = CatalogueEntry(
                    key = null,
                    fedId = e.id,
                    englishName = e.displayName,
                    muscleGroup = MuscleGroup.fromKey(e.muscleGroup),
                    equipment = Equipment.fromKey(e.equipment),
                    muscles = muscles.musclesOf(id),
                    instructions = if (e.instructions.isEmpty()) emptyMap() else mapOf("en" to e.instructions),
                )
            }
            for (e in core) {
                val id = catalogExerciseId(e.key)
                map[id] = CatalogueEntry(
                    key = e.key,
                    fedId = null,
                    englishName = e.name["en"] ?: e.key,
                    muscleGroup = MuscleGroup.fromKey(e.muscleGroup),
                    equipment = Equipment.fromKey(e.equipment),
                    muscles = muscles.musclesOf(id),
                    instructions = e.instructions,
                )
            }
            return CatalogueIndex(map)
        }
    }
}
