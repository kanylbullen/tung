package io.github.kanylbullen.tung.core

import io.github.kanylbullen.tung.core.command.ExerciseRef

/**
 * The fixed list of muscles the catalogue names in `primaryMuscles` and
 * `secondaryMuscles` (`catalog/README.md`). Finer than [MuscleGroup], which
 * stays the display level: every muscle belongs to one group, and
 * [MuscleGroup.FULLBODY] has no muscle of its own. [key] is what the JSON
 * says; a key never changes once it exists, like a catalogue key.
 */
enum class Muscle(val key: String, val group: MuscleGroup) {
    CHEST("chest", MuscleGroup.CHEST),
    LATS("lats", MuscleGroup.BACK),
    UPPER_BACK("upper_back", MuscleGroup.BACK),
    LOWER_BACK("lower_back", MuscleGroup.BACK),
    TRAPS("traps", MuscleGroup.BACK),
    FRONT_DELTS("front_delts", MuscleGroup.SHOULDERS),
    SIDE_DELTS("side_delts", MuscleGroup.SHOULDERS),
    REAR_DELTS("rear_delts", MuscleGroup.SHOULDERS),
    BICEPS("biceps", MuscleGroup.BICEPS),
    TRICEPS("triceps", MuscleGroup.TRICEPS),
    FOREARMS("forearms", MuscleGroup.BICEPS),
    QUADS("quads", MuscleGroup.LEGS),
    HAMSTRINGS("hamstrings", MuscleGroup.LEGS),
    GLUTES("glutes", MuscleGroup.GLUTES),
    CALVES("calves", MuscleGroup.LEGS),
    ADDUCTORS("adductors", MuscleGroup.LEGS),
    ABDUCTORS("abductors", MuscleGroup.LEGS),
    ABS("abs", MuscleGroup.CORE),
    OBLIQUES("obliques", MuscleGroup.CORE),
    HIP_FLEXORS("hip_flexors", MuscleGroup.CORE),
    NECK("neck", MuscleGroup.BACK);

    companion object {
        /** The muscle with [key], or null for a key outside the list (the catalogue tests reject those). */
        fun fromKey(key: String): Muscle? = entries.firstOrNull { it.key == key }
    }
}

/** Whether [muscleGroup] (a key) is what the catalogue rule allows for an entry whose first primary muscle is [firstPrimary]. */
fun muscleGroupMatches(muscleGroup: String, firstPrimary: Muscle): Boolean =
    muscleGroup == MuscleGroup.FULLBODY.key || muscleGroup == MuscleGroup.of(firstPrimary).key

/** What an exercise trains: [primary] is never empty, in the catalogue's order; [secondary] may be. */
data class ExerciseMuscles(val primary: List<Muscle>, val secondary: List<Muscle>) {
    init {
        require(primary.isNotEmpty()) { "an exercise trains at least one primary muscle" }
    }

    /** The first primary muscle: the one the display group and the picker's heuristic go by. */
    val main: Muscle get() = primary.first()

    val group: MuscleGroup get() = MuscleGroup.of(main)
}

/**
 * The muscles of every catalogue exercise, from `core.json` and
 * `extended.json`. [musclesOf] takes what callers have: a database id
 * ([catalogExerciseId] or [extendedExerciseId]), a core key (`bench_press`)
 * or an extended id (`Zercher_Squats`). Custom exercises are not in the
 * catalogue and have none. Unknown muscle keys in the files are skipped (the
 * catalogue tests keep them out); an entry left without a primary muscle is
 * left out.
 */
class MuscleIndex private constructor(private val byName: Map<String, ExerciseMuscles>) {

    fun musclesOf(idOrKey: String): ExerciseMuscles? = byName[idOrKey]

    fun musclesOf(ref: ExerciseRef): ExerciseMuscles? = when (ref) {
        is ExerciseRef.ById -> musclesOf(ref.id)
        is ExerciseRef.ByKey -> musclesOf(catalogExerciseId(ref.nameKey))
        is ExerciseRef.Extended -> musclesOf(extendedExerciseId(ref.fedId))
        is ExerciseRef.ByName -> null
    }

    val size: Int get() = byName.size

    companion object {
        val EMPTY = MuscleIndex(emptyMap())

        fun build(core: List<CoreExercise>, extended: List<ExtendedExercise>): MuscleIndex {
            val map = HashMap<String, ExerciseMuscles>()
            // Extended first, so a core key spelled like an extended id keeps the core entry.
            for (e in extended) {
                val muscles = muscles(e.primaryMuscles, e.secondaryMuscles) ?: continue
                map[e.id] = muscles
                map[extendedExerciseId(e.id)] = muscles
            }
            for (e in core) {
                val muscles = muscles(e.primaryMuscles, e.secondaryMuscles) ?: continue
                map[e.key] = muscles
                map[catalogExerciseId(e.key)] = muscles
            }
            return MuscleIndex(map)
        }

        private fun muscles(primary: List<String>, secondary: List<String>): ExerciseMuscles? {
            val p = primary.mapNotNull(Muscle::fromKey).distinct()
            if (p.isEmpty()) return null
            return ExerciseMuscles(p, secondary.mapNotNull(Muscle::fromKey).distinct() - p.toSet())
        }
    }
}
