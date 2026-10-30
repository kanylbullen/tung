package io.github.kanylbullen.tung.core.ai

import io.github.kanylbullen.tung.core.MAX_REPS
import io.github.kanylbullen.tung.core.MAX_TARGET_SETS
import io.github.kanylbullen.tung.core.MAX_WEIGHT_KG
import io.github.kanylbullen.tung.core.MIN_TARGET_SETS
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.ExerciseCandidate
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.command.ExerciseResolver
import io.github.kanylbullen.tung.core.command.MAX_PLANNED_SETS
import io.github.kanylbullen.tung.core.command.MIN_PLANNED_SETS
import io.github.kanylbullen.tung.core.command.PlannedExercise
import io.github.kanylbullen.tung.core.command.PlannedSet
import io.github.kanylbullen.tung.core.command.RefResolution
import io.github.kanylbullen.tung.core.command.RoutinePlan
import io.github.kanylbullen.tung.core.command.RoutinePlanExercise
import io.github.kanylbullen.tung.core.command.Validation
import io.github.kanylbullen.tung.core.command.WorkoutPlan
import io.github.kanylbullen.tung.core.command.resolveRef
import io.github.kanylbullen.tung.core.command.validate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.datetime.LocalDate
import kotlin.math.floor

/*
 * A model's answer, in two steps. [parseAiAnswer] reads the JSON the prompt
 * asked for ([buildAiPrompt]) into plans that name exercises by reference
 * (catalogue key when the key is in the catalogue, else the name), dropping
 * entries whose numbers are outside what `validate` accepts. [resolveAiAnswer]
 * then resolves every reference the way a command would ([resolveRef]) and
 * drops what resolves to nothing: a suggestion never shows an exercise that is
 * not there. A plan left empty is rejected with a reason.
 */

/** A program answer before resolution: routines in the shape `save_routine_plan` takes. */
sealed interface AiDraft {
    /** Entries left out while parsing: numbers out of range, no exercise named, a routine without a name. */
    val dropped: Int

    data class Program(
        val rationale: String,
        val programName: String?,
        val routines: List<RoutinePlan>,
        override val dropped: Int,
    ) : AiDraft

    data class NextWorkout(val rationale: String, val plan: WorkoutPlan, override val dropped: Int) : AiDraft

    data class Plateaus(val plateaus: List<AiPlateauDraft>, override val dropped: Int) : AiDraft

    /** The import review's matches ([AiRequest.MatchExercises]), one per name at most. */
    data class Matches(val matches: List<AiMatchDraft>, override val dropped: Int) : AiDraft
}

/** One plateau as the model wrote it; [since] is null when it gave no valid ISO date. */
data class AiPlateauDraft(val exercise: ExerciseRef, val since: LocalDate?, val observation: String, val suggestion: String)

sealed interface AiParse {
    data class Parsed(val draft: AiDraft) : AiParse

    /** Not the JSON asked for. [detail] says what was wrong, in English, for a log or a test; not for the screen. */
    data class Malformed(val detail: String) : AiParse
}

private val lenient = Json { isLenient = true }

/**
 * Reads [text], the model's answer to [request]. Tolerates a code fence or
 * words around the object (the first `{` to the last `}` is read), and numbers
 * written as strings. An exercise is [ExerciseRef.ByKey] when its `key` is in
 * [catalogueKeys], else [ExerciseRef.ByName] of its `name` (or of the key in
 * words, `cable_fly` → "cable fly"); an entry with neither is dropped.
 */
fun parseAiAnswer(request: AiRequest, text: String, catalogueKeys: Set<String>): AiParse {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return AiParse.Malformed("no JSON object in the answer")
    val root = runCatching { lenient.parseToJsonElement(text.substring(start, end + 1)) }.getOrNull() as? JsonObject
        ?: return AiParse.Malformed("the answer is not valid JSON")
    return when (request) {
        AiRequest.Program -> parseProgram(root, catalogueKeys)
        AiRequest.NextWorkout, is AiRequest.FreeText -> parseNextWorkout(root, catalogueKeys)
        AiRequest.Plateaus -> parsePlateaus(root, catalogueKeys)
        is AiRequest.MatchExercises -> parseMatches(root, request, catalogueKeys)
    }
}

private fun parseProgram(root: JsonObject, keys: Set<String>): AiParse {
    val routines = root.array("routines") ?: return AiParse.Malformed("no routines list")
    var dropped = 0
    val plans = routines.mapNotNull { element ->
        val routine = element as? JsonObject ?: return@mapNotNull null.also { dropped++ }
        val name = routine.string("name")?.trim()
        if (name.isNullOrEmpty()) return@mapNotNull null.also { dropped++ }
        val exercises = routine.array("exercises").orEmpty().mapNotNull { e ->
            val exercise = e as? JsonObject ?: return@mapNotNull null.also { dropped++ }
            val ref = refOf(exercise, keys)
            val sets = exercise.int("target_sets")
            val reps = exercise.int("target_reps")?.takeIf { it != 0 }
            val ok = ref != null && sets != null && sets in MIN_TARGET_SETS..MAX_TARGET_SETS && (reps == null || reps in 1..MAX_REPS)
            if (!ok || ref == null || sets == null) null.also { dropped++ } else RoutinePlanExercise(ref, sets, reps)
        }
        RoutinePlan(name, exercises)
    }
    return AiParse.Parsed(AiDraft.Program(root.string("rationale").orEmpty().trim(), root.string("program_name")?.trim()?.ifEmpty { null }, plans, dropped))
}

private fun parseNextWorkout(root: JsonObject, keys: Set<String>): AiParse {
    val exercises = root.array("exercises") ?: return AiParse.Malformed("no exercises list")
    var dropped = 0
    val planned = exercises.mapNotNull { element ->
        val exercise = element as? JsonObject ?: return@mapNotNull null.also { dropped++ }
        val ref = refOf(exercise, keys) ?: return@mapNotNull null.also { dropped++ }
        val sets = exercise.array("sets").orEmpty().map { s ->
            val set = s as? JsonObject ?: return@mapNotNull null.also { dropped++ }
            val kg = set.double("weight_kg")
            val reps = set.int("reps")
            if ((kg != null && (kg.isNaN() || kg < 0.0 || kg > MAX_WEIGHT_KG)) || (reps != null && reps !in 0..MAX_REPS)) {
                return@mapNotNull null.also { dropped++ }
            }
            PlannedSet(weightKg = kg, reps = reps)
        }
        if (sets.size !in MIN_PLANNED_SETS..MAX_PLANNED_SETS) null.also { dropped++ } else PlannedExercise(ref, sets)
    }
    return AiParse.Parsed(AiDraft.NextWorkout(root.string("rationale").orEmpty().trim(), WorkoutPlan(planned), dropped))
}

private fun parsePlateaus(root: JsonObject, keys: Set<String>): AiParse {
    val plateaus = root.array("plateaus") ?: return AiParse.Malformed("no plateaus list")
    var dropped = 0
    val drafts = plateaus.mapNotNull { element ->
        val plateau = element as? JsonObject ?: return@mapNotNull null.also { dropped++ }
        val ref = refOf(plateau, keys) ?: return@mapNotNull null.also { dropped++ }
        val since = plateau.string("since")?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
        AiPlateauDraft(ref, since, plateau.string("observation").orEmpty().trim(), plateau.string("suggestion").orEmpty().trim())
    }
    return AiParse.Parsed(AiDraft.Plateaus(drafts, dropped))
}

/** `key` in the catalogue → by key; else `name` → by name; else an unknown key read as words; else nothing. */
private fun refOf(o: JsonObject, keys: Set<String>): ExerciseRef? {
    val key = o.string("key")?.trim()?.ifEmpty { null }
    val name = o.string("name")?.trim()?.ifEmpty { null }
    return when {
        key != null && key in keys -> ExerciseRef.ByKey(key)
        name != null -> ExerciseRef.ByName(name)
        key != null -> ExerciseRef.ByName(key.replace('_', ' '))
        else -> null
    }
}

internal fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray

internal fun JsonObject.primitive(name: String): JsonPrimitive? = (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }

internal fun JsonObject.string(name: String): String? = primitive(name)?.content

private fun JsonObject.double(name: String): Double? = primitive(name)?.content?.trim()?.toDoubleOrNull()

/** A whole number, also when written as `8.0` or `"8"`. */
private fun JsonObject.int(name: String): Int? {
    val d = double(name) ?: return null
    return if (d == floor(d) && d in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) d.toInt() else null
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

// --- resolution ---

/** What the screen shows and the user confirms: every exercise resolved, with the count of entries left out. */
sealed interface AiProposal {
    /** Suggestions left out: unreadable entries ([AiDraft.dropped]) plus exercises that resolved to nothing. */
    val dropped: Int

    data class Program(
        val rationale: String,
        val programName: String?,
        val routines: List<ProposedRoutine>,
        override val dropped: Int,
    ) : AiProposal

    data class NextWorkout(val rationale: String, val exercises: List<ProposedExercise>, override val dropped: Int) : AiProposal {
        /** The plan for `apply_plan`, naming each exercise by what it resolved to. */
        fun toPlan(): WorkoutPlan = WorkoutPlan(exercises.map { PlannedExercise(it.exercise.toRef(), it.sets) })
    }

    data class Plateaus(val plateaus: List<ProposedPlateau>, override val dropped: Int) : AiProposal

    /** Matches for the import review, in the order the model gave them; shown on the rows, applied only by a tap. */
    data class Matches(val matches: List<ProposedMatch>, override val dropped: Int) : AiProposal
}

data class ProposedRoutine(val name: String, val exercises: List<ProposedRoutineExercise>) {
    /** The plan for `save_routine_plan`, naming each exercise by what it resolved to. */
    fun toPlan(): RoutinePlan = RoutinePlan(name, exercises.map { RoutinePlanExercise(it.exercise.toRef(), it.targetSets, it.targetReps) })
}

data class ProposedRoutineExercise(val exercise: ExerciseCandidate, val targetSets: Int, val targetReps: Int?)

data class ProposedExercise(val exercise: ExerciseCandidate, val sets: List<PlannedSet>)

data class ProposedPlateau(val exercise: ExerciseCandidate, val since: LocalDate?, val observation: String, val suggestion: String)

/** The reference a command uses for a resolved exercise: its row, or its extended entry when it has no row yet. */
fun ExerciseCandidate.toRef(): ExerciseRef = extendedId?.let { ExerciseRef.Extended(it) } ?: ExerciseRef.ById(id)

sealed interface AiResolution {
    data class Proposed(val proposal: AiProposal) : AiResolution

    /** Nothing usable was left: no routine, or no exercise. [detail] is English, for a log or a test. */
    data class Empty(val detail: String) : AiResolution
}

/**
 * Resolves every exercise of [draft] over [resolver] and drops the ones that
 * resolve to nothing (an unknown key, a name with no single exact match).
 * A routine left without exercises is dropped; a program left without
 * routines, or a workout without exercises, is [AiResolution.Empty]. Each
 * plan that remains passes core's [validate] for the command it becomes.
 * Plateaus may be an empty list: that is an answer ("no plateaus"), and so
 * may matches; a match whose ref resolves to nothing is dropped, a null ref
 * ("no good match") is kept.
 */
suspend fun resolveAiAnswer(draft: AiDraft, resolver: ExerciseResolver): AiResolution {
    var dropped = draft.dropped
    suspend fun resolve(ref: ExerciseRef): ExerciseCandidate? =
        (resolveRef(ref, resolver) as? RefResolution.Resolved)?.exercise.also { if (it == null) dropped++ }

    return when (draft) {
        is AiDraft.Program -> {
            val routines = draft.routines.mapNotNull { routine ->
                val exercises = routine.exercises.mapNotNull { e -> resolve(e.exercise)?.let { ProposedRoutineExercise(it, e.targetSets, e.targetReps) } }
                val proposed = ProposedRoutine(routine.name, exercises)
                if (exercises.isEmpty() || validate(Command.SaveRoutinePlan(proposed.toPlan())) !is Validation.Valid) {
                    null.also { dropped++ }
                } else {
                    proposed
                }
            }
            if (routines.isEmpty()) {
                AiResolution.Empty("no routine with an exercise from the catalogue")
            } else {
                AiResolution.Proposed(AiProposal.Program(draft.rationale, draft.programName, routines, dropped))
            }
        }
        is AiDraft.NextWorkout -> {
            val exercises = draft.plan.exercises.mapNotNull { e -> resolve(e.exercise)?.let { ProposedExercise(it, e.sets) } }
            val proposal = AiProposal.NextWorkout(draft.rationale, exercises, dropped)
            if (exercises.isEmpty() || validate(Command.ApplyPlan(proposal.toPlan())) !is Validation.Valid) {
                AiResolution.Empty("no exercise from the catalogue")
            } else {
                AiResolution.Proposed(proposal)
            }
        }
        is AiDraft.Plateaus -> {
            val plateaus = draft.plateaus.mapNotNull { p -> resolve(p.exercise)?.let { ProposedPlateau(it, p.since, p.observation, p.suggestion) } }
            AiResolution.Proposed(AiProposal.Plateaus(plateaus, dropped))
        }
        is AiDraft.Matches -> {
            // A ref that names nothing in the catalogue or the extended catalogue drops the entry; null is an answer.
            val matches = draft.matches.mapNotNull { m ->
                if (m.ref == null) {
                    ProposedMatch(m.name, null, m.confidence, m.note)
                } else {
                    resolve(m.ref)?.let { ProposedMatch(m.name, it, m.confidence, m.note) }
                }
            }
            AiResolution.Proposed(AiProposal.Matches(matches, dropped))
        }
    }
}
