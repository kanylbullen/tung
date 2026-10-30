package io.github.kanylbullen.tung.core.importing

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.command.ExerciseCandidate
import io.github.kanylbullen.tung.core.command.MAX_SUGGESTIONS
import io.github.kanylbullen.tung.core.command.normalizeExerciseName
import io.github.kanylbullen.tung.core.command.rankByName

/*
 * The review's offline suggestions for a name nothing matched exactly. Source
 * apps put the load in the name ("Dumbbell Deadlift", "Machine Seated Row"),
 * and the load matters more than the words: a dumbbell deadlift logged as the
 * barbell deadlift poisons "Last" and the pre-fill (the alias table's rule,
 * `catalog/README.md`). So the equipment word is read off the name, the rest
 * is matched, and among equally good matches the ones with the same equipment
 * come first. A suggestion is only ever shown; the person taps it.
 */

/** The words that name a load, normalised ([normalizeExerciseName]), and what they mean. */
private val EQUIPMENT_WORDS: Map<String, Equipment> = mapOf(
    "barbell" to Equipment.BARBELL,
    "barbells" to Equipment.BARBELL,
    "bb" to Equipment.BARBELL,
    "ez" to Equipment.EZ_BAR,
    "dumbbell" to Equipment.DUMBBELL,
    "dumbbells" to Equipment.DUMBBELL,
    "db" to Equipment.DUMBBELL,
    "kettlebell" to Equipment.KETTLEBELL,
    "kettlebells" to Equipment.KETTLEBELL,
    "kb" to Equipment.KETTLEBELL,
    "machine" to Equipment.MACHINE,
    "smith" to Equipment.MACHINE,
    "lever" to Equipment.MACHINE,
    "leverage" to Equipment.MACHINE,
    "cable" to Equipment.CABLE,
    "cables" to Equipment.CABLE,
    "band" to Equipment.BAND,
    "bands" to Equipment.BAND,
    "banded" to Equipment.BAND,
    "bodyweight" to Equipment.BODYWEIGHT,
)

/**
 * The equipment [name] says it is done with: "Dumbbell Deadlift" → dumbbell,
 * "Machine Lat Pulldown" → machine, "Body weight squat" → bodyweight. Null
 * when the name names none, or two different ones ("Barbell or dumbbell row").
 */
fun inferEquipment(name: String): Equipment? {
    val words = normalizeExerciseName(name).split(' ')
    val found = words.mapNotNull { EQUIPMENT_WORDS[it] }.toMutableSet()
    if (words.zipWithNext().any { (a, b) -> a == "body" && b == "weight" }) found += Equipment.BODYWEIGHT
    return found.singleOrNull()
}

/** [name] normalised, without its equipment words (and "bar" after "ez", "weight" after "body"). */
internal fun withoutEquipmentWords(name: String): String {
    val words = normalizeExerciseName(name).split(' ')
    val kept = words.filterIndexed { i, w ->
        val previous = words.getOrNull(i - 1)
        w !in EQUIPMENT_WORDS && !(w == "bar" && previous == "ez") && !(w == "weight" && previous == "body") && !(w == "body" && words.getOrNull(i + 1) == "weight")
    }
    return kept.joinToString(" ")
}

/**
 * Up to [limit] suggestions for [name] among [candidates], best first. The
 * words other than the equipment are matched the way `matchExerciseName`
 * scores them (the whole name when nothing else is left); among equal scores
 * a candidate with the equipment [inferEquipment] reads off the name comes
 * first, then the usual order (rows before extended entries, shorter names).
 */
fun importSuggestions(name: String, candidates: List<ExerciseCandidate>, limit: Int = MAX_SUGGESTIONS): List<ExerciseCandidate> {
    val unique = candidates.distinctBy { it.id }
    val equipment = inferEquipment(name)
    val rest = withoutEquipmentWords(name).ifEmpty { normalizeExerciseName(name) }
    val ranked = rankByName(rest, unique)
    val order = compareByDescending<Pair<ExerciseCandidate, Int>> { it.second }
        .thenBy { if (equipment != null && it.first.equipment == equipment) 0 else 1 }
    // sortedWith is stable: within a score and an equipment, rankByName's order stays.
    return ranked.sortedWith(order).map { it.first }.take(limit)
}

/** How much of an export uses one exercise name: the workouts it was logged in, its sets there, and the routines that list it. */
data class NameUsage(val workouts: Int, val sets: Int, val routines: Int)

/** [NameUsage] for every name of [importExerciseNames], so the review can say which names carry history. */
fun ImportBundle.nameUsage(): Map<String, NameUsage> = importExerciseNames().associateWith { name ->
    val inWorkouts = workouts.map { w -> w.exercises.filter { it.name == name } }.filter { it.isNotEmpty() }
    NameUsage(
        workouts = inWorkouts.size,
        sets = inWorkouts.sumOf { exercises -> exercises.sumOf { it.sets.size } },
        routines = routines.count { r -> r.exercises.any { it.name == name } },
    )
}
