package io.github.kanylbullen.tung.core.picker

import io.github.kanylbullen.tung.core.Muscle
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.MuscleIndex
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.extendedExerciseId

/** A finished workout as the picker learns from it: when it started and its exercise ids in position order. */
data class HistoricWorkout(val startedAt: Long, val exerciseIdsInOrder: List<String>)

/**
 * An exercise the picker may suggest. [name] is the displayed name, the tie-break
 * after the score; [muscleGroup] is the stored group, used when [MuscleIndex]
 * has no muscles for it (a custom exercise).
 */
data class Candidate(val exerciseId: String, val name: String, val muscleGroup: MuscleGroup = MuscleGroup.FULLBODY)

/** Why an exercise is suggested: the strongest contribution to its score. */
sealed interface SuggestionReason {
    /** Learned: the user often does it right after [exerciseId], the last exercise in the workout. */
    data class OftenAfter(val exerciseId: String) : SuggestionReason

    /** Learned: it often shares a workout with the exercises already in this one. */
    data object OftenTogether : SuggestionReason

    /** Heuristic: its main muscle complements [muscle], the main muscle of the last exercise. */
    data class PairsWith(val muscle: Muscle) : SuggestionReason

    /** Heuristic: its muscle group is not in the workout yet. */
    data object NotYetToday : SuggestionReason
}

data class Suggestion(val exerciseId: String, val score: Double, val reason: SuggestionReason)

/** How many finished workouts, newest first, the learned layer reads. */
const val SUGGESTION_HISTORY_LIMIT = 60

/** The picker's default number of suggestions. */
const val SUGGESTION_LIMIT = 6

private const val TRANSITION_WEIGHT = 3.0
private const val TOGETHER_WEIGHT = 1.0
private const val OLDEST_RECENCY = 0.5

/** Learned scores are multiplied by this once the last exercise has [HANDOVER_OBSERVATIONS] observations. */
private const val HANDOVER_SCALE = 3.0
private const val HANDOVER_OBSERVATIONS = 3

/** The heuristic is always added, at this weight. */
private const val HEURISTIC_WEIGHT = 0.5
private const val COMPLEMENT_POINTS = 2.0
private const val NEW_GROUP_POINTS = 1.0
private const val SAME_MUSCLE_POINTS = -2.0
private const val CORE_LAST_POINTS = -1.0

/** Pairs that are trained one after the other: antagonists, push and pull. Symmetric. */
private val COMPLEMENTS: Map<Muscle, Set<Muscle>> = listOf(
    Muscle.BICEPS to Muscle.TRICEPS,
    Muscle.CHEST to Muscle.LATS,
    Muscle.CHEST to Muscle.UPPER_BACK,
    Muscle.QUADS to Muscle.HAMSTRINGS,
    Muscle.FRONT_DELTS to Muscle.REAR_DELTS,
    Muscle.ABS to Muscle.LOWER_BACK,
).flatMap { (a, b) -> listOf(a to b, b to a) }
    .groupBy({ it.first }, { it.second })
    .mapValues { it.value.toSet() }

/** Core muscles, which the heuristic keeps for the end of a workout. */
private val CORE_LAST = setOf(Muscle.ABS, Muscle.OBLIQUES)

/**
 * Ranks [candidates] for the exercise picker of a workout that already holds
 * [currentWorkout]; pure, so the app and the server rank the same way. Up to
 * [limit] suggestions, best first, each with the reason that counted most.
 *
 * Two layers, both computed for every candidate B, with A the last exercise
 * in the workout:
 *
 * - **Learned**, from [history] (the latest [SUGGESTION_HISTORY_LIMIT]
 *   finished workouts that started no later than [now]): B right after A in a
 *   workout counts [TRANSITION_WEIGHT], B in a workout with any exercise of
 *   this one counts [TOGETHER_WEIGHT], each times a recency factor that falls
 *   linearly from 1.0 for the newest workout to 0.5 for the 60th. Nothing is
 *   stored: it is derived from history every time.
 * - **Heuristic**, added at [HEURISTIC_WEIGHT]: +2 when B's main muscle
 *   complements A's (biceps and triceps, chest and lats or upper back, quads
 *   and hamstrings, front and rear delts, abs and lower back), +1 when B's
 *   group is not in the workout yet, −2 when B's main muscle is A's, −1 for an
 *   abs or obliques exercise unless nothing else is left (core last).
 *
 * **Hand-over:** once A has [HANDOVER_OBSERVATIONS] observations (recent
 * workouts with A and at least one other exercise), the learned score is
 * multiplied by [HANDOVER_SCALE] so the user's habits win; before that the
 * heuristic decides between candidates the history does not separate.
 *
 * Exercises already in the workout are never suggested, an empty workout gets
 * no suggestions, and a candidate needs a positive score. Order: score, then
 * name (case-insensitive), then id, so equal input gives equal output.
 */
fun suggest(
    currentWorkout: List<ExerciseRef>,
    history: List<HistoricWorkout>,
    candidates: List<Candidate>,
    muscles: MuscleIndex,
    now: Long,
    limit: Int = SUGGESTION_LIMIT,
): List<Suggestion> {
    val currentIds = currentWorkout.mapNotNull { it.exerciseId() }
    if (currentIds.isEmpty() || limit <= 0) return emptyList()
    val last = currentIds.last()
    val inWorkout = currentIds.toSet()
    val pool = candidates.distinctBy { it.exerciseId }.filter { it.exerciseId !in inWorkout }
    if (pool.isEmpty()) return emptyList()

    // --- learned ---
    val transitions = HashMap<String, Double>()
    val together = HashMap<String, Double>()
    var observations = 0
    history.filter { it.startedAt <= now }
        .sortedByDescending { it.startedAt }
        .take(SUGGESTION_HISTORY_LIMIT)
        .forEachIndexed { index, workout ->
            val recency = 1.0 - (1.0 - OLDEST_RECENCY) * index / (SUGGESTION_HISTORY_LIMIT - 1)
            val ids = workout.exerciseIdsInOrder
            val distinct = ids.toSet()
            if (last in distinct && distinct.size > 1) observations++
            ids.zipWithNext().filter { (a, b) -> a == last && b != last }.mapTo(HashSet()) { it.second }
                .forEach { transitions[it] = (transitions[it] ?: 0.0) + recency }
            if (distinct.any { it in inWorkout }) {
                distinct.filter { it !in inWorkout }.forEach { together[it] = (together[it] ?: 0.0) + recency }
            }
        }
    val scale = if (observations >= HANDOVER_OBSERVATIONS) HANDOVER_SCALE else 1.0

    // --- heuristic ---
    val candidateGroups = candidates.associate { it.exerciseId to it.muscleGroup }
    fun mainMuscle(id: String): Muscle? = muscles.musclesOf(id)?.main
    fun groupOf(id: String): MuscleGroup? = muscles.musclesOf(id)?.group ?: candidateGroups[id]
    val lastMain = mainMuscle(last)
    val groupsInWorkout = currentIds.mapNotNullTo(HashSet()) { groupOf(it) }
    val onlyCoreLeft = pool.all { mainMuscle(it.exerciseId) in CORE_LAST }

    return pool.mapNotNull { candidate ->
        val id = candidate.exerciseId
        val main = mainMuscle(id)
        val group = muscles.musclesOf(id)?.group ?: candidate.muscleGroup
        val complement = lastMain != null && main != null && main in COMPLEMENTS[lastMain].orEmpty()
        val newGroup = group !in groupsInWorkout
        var heuristic = 0.0
        if (complement) heuristic += COMPLEMENT_POINTS
        if (newGroup) heuristic += NEW_GROUP_POINTS
        if (lastMain != null && main == lastMain) heuristic += SAME_MUSCLE_POINTS
        if (main in CORE_LAST && !onlyCoreLeft) heuristic += CORE_LAST_POINTS

        val after = TRANSITION_WEIGHT * transitions[id].orZero() * scale
        val with = TOGETHER_WEIGHT * together[id].orZero() * scale
        val score = after + with + HEURISTIC_WEIGHT * heuristic

        // The strongest positive contribution; on a tie the first in this list.
        val contributions = buildList<Pair<SuggestionReason, Double>> {
            add(SuggestionReason.OftenAfter(last) to after)
            add(SuggestionReason.OftenTogether to with)
            if (complement && lastMain != null) add(SuggestionReason.PairsWith(lastMain) to HEURISTIC_WEIGHT * COMPLEMENT_POINTS)
            if (newGroup) add(SuggestionReason.NotYetToday to HEURISTIC_WEIGHT * NEW_GROUP_POINTS)
        }
        val reason = contributions.filter { it.second > 0.0 }.maxByOrNull { it.second }?.first
        if (score > 0.0 && reason != null) (Suggestion(id, score, reason) to candidate.name) else null
    }
        .sortedWith(
            compareByDescending<Pair<Suggestion, String>> { it.first.score }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.second }
                .thenBy { it.first.exerciseId },
        )
        .take(limit)
        .map { it.first }
}

private fun Double?.orZero(): Double = this ?: 0.0

/** The database id a reference names, or null for words that still need resolving. */
private fun ExerciseRef.exerciseId(): String? = when (this) {
    is ExerciseRef.ById -> id
    is ExerciseRef.ByKey -> catalogExerciseId(nameKey)
    is ExerciseRef.Extended -> extendedExerciseId(fedId)
    is ExerciseRef.ByName -> null
}
