package io.github.kanylbullen.tung.core.command

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.normalizeForSearch
import io.github.kanylbullen.tung.core.prefillSet

/**
 * An exercise as resolution sees it: its database [id], every name it answers
 * to ([names]: the catalogue's names in each language, a custom exercise's
 * name, later aliases; the first is the one to show), its catalogue [nameKey]
 * if it has one, and [extendedId] when it is an extended-catalogue entry that
 * may not have a database row yet (its [id] is then
 * [io.github.kanylbullen.tung.core.extendedExerciseId], and the handler
 * creates the row before using it).
 */
data class ExerciseCandidate(
    val id: String,
    val names: List<String>,
    val nameKey: String? = null,
    val extendedId: String? = null,
) {
    val displayName: String get() = names.firstOrNull().orEmpty()
}

/**
 * What resolution needs from wherever the exercises live. Core defines it,
 * the app implements it over Room and the catalogue files
 * (`AppExerciseResolver`), a server will over its own store.
 */
interface ExerciseResolver {
    /** The exercise with catalogue key [nameKey], or null. */
    suspend fun lookupKey(nameKey: String): ExerciseCandidate?

    /** The exercise with database id [id], or null. */
    suspend fun lookupId(id: String): ExerciseCandidate?

    /** The extended-catalogue entry [fedId] (free-exercise-db id), or null. */
    suspend fun lookupExtended(fedId: String): ExerciseCandidate?

    /**
     * The exercises that may answer to [text]. A superset is fine (the app
     * returns every exercise it has); [matchExerciseName] decides what is an
     * exact match and what is only a suggestion.
     */
    suspend fun searchByName(text: String): List<ExerciseCandidate>
}

/** An [ExerciseResolver] over a list in memory: for tests, and for any caller that already holds the catalogue. */
class CandidateResolver(private val candidates: List<ExerciseCandidate>) : ExerciseResolver {
    override suspend fun lookupKey(nameKey: String) = candidates.firstOrNull { it.nameKey == nameKey }
    override suspend fun lookupId(id: String) = candidates.firstOrNull { it.id == id }
    override suspend fun lookupExtended(fedId: String) = candidates.firstOrNull { it.extendedId == fedId }
    override suspend fun searchByName(text: String) = candidates
}

/** A name the caller gave that did not resolve, with what it might have meant (best first; may be empty). */
data class UnresolvedExercise(val text: String, val suggestions: List<ExerciseCandidate>)

/** How many suggestions a question carries at most. */
const val MAX_SUGGESTIONS = 5

private val nonWord = Regex("[^\\p{L}\\p{N}]+")

/**
 * The form names are compared in: [normalizeForSearch] (lowercase, no
 * diacritics, the same as the picker's search), with punctuation and runs of
 * spaces as one space, so "Chin-up", "chin up" and "CHIN  UP" are equal.
 */
fun normalizeExerciseName(text: String): String =
    normalizeForSearch(text).replace(nonWord, " ").trim()

/** The outcome of [matchExerciseName]. */
sealed interface NameMatch {
    data class Exact(val candidate: ExerciseCandidate) : NameMatch

    /** No single exact match: several exact ones, or only partial ones (best first, possibly none). */
    data class Unsure(val suggestions: List<ExerciseCandidate>) : NameMatch
}

/**
 * Matches [text] against [candidates]' names, never guessing.
 *
 * Only an **exact** match (after [normalizeExerciseName]) resolves, and only
 * when one exercise has it. An exercise that has a database row beats an
 * extended-catalogue entry with the same name, so "Deadlift" is the core
 * catalogue's even if the extended catalogue has one too; two rows with the
 * same name (a custom "Deadlift" next to the catalogue's) are a question.
 *
 * Anything else is [NameMatch.Unsure] with up to [MAX_SUGGESTIONS]
 * suggestions: names that contain the text first, then names sharing the most
 * words with it (a word of the text matching the start of a word of the name,
 * so "bar" suggests "Barbell curl"), rows before extended entries, shorter
 * names first.
 */
fun matchExerciseName(text: String, candidates: List<ExerciseCandidate>): NameMatch {
    val query = normalizeExerciseName(text)
    if (query.isEmpty()) return NameMatch.Unsure(emptyList())
    val unique = candidates.distinctBy { it.id }
    val forms = unique.associateWith { c -> c.names.map(::normalizeExerciseName) }

    val exact = unique.filter { c -> forms.getValue(c).any { it == query } }
    val exactRows = exact.filter { it.extendedId == null }
    when {
        exactRows.size == 1 -> return NameMatch.Exact(exactRows.single())
        exactRows.isEmpty() && exact.size == 1 -> return NameMatch.Exact(exact.single())
        exact.isNotEmpty() -> return NameMatch.Unsure(exact.sortedWith(suggestionOrder(forms)).take(MAX_SUGGESTIONS))
    }

    val words = query.split(' ')
    val scored = unique.mapNotNull { c ->
        val names = forms.getValue(c)
        val score = names.maxOf { name ->
            if (name.contains(query)) {
                words.size + 1
            } else {
                val nameWords = name.split(' ')
                words.count { w -> nameWords.any { it.startsWith(w) } }
            }
        }
        if (score > 0) c to score else null
    }
    val suggestions = scored
        .sortedWith(compareByDescending<Pair<ExerciseCandidate, Int>> { it.second }.thenComparator { a, b -> suggestionOrder(forms).compare(a.first, b.first) })
        .map { it.first }
        .take(MAX_SUGGESTIONS)
    return NameMatch.Unsure(suggestions)
}

private fun suggestionOrder(forms: Map<ExerciseCandidate, List<String>>): Comparator<ExerciseCandidate> =
    compareBy<ExerciseCandidate> { it.extendedId != null }
        .thenBy { c -> forms.getValue(c).minOfOrNull { it.length } ?: 0 }
        .thenBy { it.displayName }

/** The outcome of resolving one [ExerciseRef]. */
sealed interface RefResolution {
    data class Resolved(val exercise: ExerciseCandidate) : RefResolution
    data class Unresolved(val question: UnresolvedExercise) : RefResolution
}

/** Resolves one reference: a key, id or extended id that exists, or a name with one exact match ([matchExerciseName]). */
suspend fun resolveRef(ref: ExerciseRef, resolver: ExerciseResolver): RefResolution {
    val found = when (ref) {
        is ExerciseRef.ByKey -> resolver.lookupKey(ref.nameKey)
        is ExerciseRef.ById -> resolver.lookupId(ref.id)
        is ExerciseRef.Extended -> resolver.lookupExtended(ref.fedId)
        is ExerciseRef.ByName -> when (val match = matchExerciseName(ref.text, resolver.searchByName(ref.text))) {
            is NameMatch.Exact -> match.candidate
            is NameMatch.Unsure -> return RefResolution.Unresolved(UnresolvedExercise(ref.text, match.suggestions))
        }
    }
    if (found != null) return RefResolution.Resolved(found)
    // An unknown key reads as words ("bench_presss" → "bench presss") for the suggestions.
    val text = when (ref) {
        is ExerciseRef.ByKey -> ref.nameKey
        is ExerciseRef.ById -> ref.id
        is ExerciseRef.Extended -> ref.fedId
        is ExerciseRef.ByName -> ref.text
    }
    val suggestions = if (ref is ExerciseRef.ByKey) {
        val words = ref.nameKey.replace('_', ' ')
        (matchExerciseName(words, resolver.searchByName(words)) as? NameMatch.Unsure)?.suggestions.orEmpty()
    } else {
        emptyList()
    }
    return RefResolution.Unresolved(UnresolvedExercise(text, suggestions))
}

/** One planned exercise with its exercise resolved. */
data class ResolvedPlannedExercise(val exercise: ExerciseCandidate, val sets: List<PlannedSet>)

/** A [WorkoutPlan] whose every exercise has an id. */
data class ResolvedWorkoutPlan(val exercises: List<ResolvedPlannedExercise>)

/** One routine exercise with its exercise resolved. */
data class ResolvedRoutineExercise(val exercise: ExerciseCandidate, val targetSets: Int, val targetReps: Int?)

/** A [RoutinePlan] whose every exercise has an id. */
data class ResolvedRoutinePlan(val name: String, val exercises: List<ResolvedRoutineExercise>)

/** The outcome of [resolvePlan] and [resolveRoutinePlan]: everything resolved, or the questions, never a guess. */
sealed interface PlanResolution<out T> {
    data class Resolved<T>(val plan: T) : PlanResolution<T>

    /** One question per exercise that did not resolve, in plan order. */
    data class NeedsAnswers(val questions: List<UnresolvedExercise>) : PlanResolution<Nothing>
}

/** Resolves every exercise of [plan]; one that does not resolve makes the whole plan a set of questions. */
suspend fun resolvePlan(plan: WorkoutPlan, catalogue: ExerciseResolver): PlanResolution<ResolvedWorkoutPlan> =
    resolveAll(plan.exercises.map { it.exercise }, catalogue) { resolved ->
        ResolvedWorkoutPlan(plan.exercises.zip(resolved) { p, e -> ResolvedPlannedExercise(e, p.sets) })
    }

/** Resolves every exercise of a routine [plan], like [resolvePlan]. */
suspend fun resolveRoutinePlan(plan: RoutinePlan, catalogue: ExerciseResolver): PlanResolution<ResolvedRoutinePlan> =
    resolveAll(plan.exercises.map { it.exercise }, catalogue) { resolved ->
        ResolvedRoutinePlan(
            plan.name.trim(),
            plan.exercises.zip(resolved) { p, e -> ResolvedRoutineExercise(e, p.targetSets, p.targetReps) },
        )
    }

private suspend fun <T> resolveAll(
    refs: List<ExerciseRef>,
    catalogue: ExerciseResolver,
    build: (List<ExerciseCandidate>) -> T,
): PlanResolution<T> {
    val results = refs.map { resolveRef(it, catalogue) }
    val questions = results.filterIsInstance<RefResolution.Unresolved>().map { it.question }
    if (questions.isNotEmpty()) return PlanResolution.NeedsAnswers(questions)
    return PlanResolution.Resolved(build(results.map { (it as RefResolution.Resolved).exercise }))
}

/**
 * The numbers each planned set starts with. A number the plan gives is used
 * as it is; a missing one comes from the pre-fill rule ([prefillSet]) at that
 * set's position: the set before it in the plan, else last session's set at
 * the same position, else 0 kg and 0 reps. Nothing is ticked by a plan, so
 * these are only what the rows show.
 */
fun plannedSetValues(sets: List<PlannedSet>, previousSession: List<LoggedSet>): List<LoggedSet> {
    val result = mutableListOf<LoggedSet>()
    sets.forEachIndexed { position, planned ->
        val base = prefillSet(result.lastOrNull(), previousSession, position) ?: LoggedSet(weightKg = 0.0, reps = 0)
        result += LoggedSet(weightKg = planned.weightKg ?: base.weightKg, reps = planned.reps ?: base.reps)
    }
    return result
}
