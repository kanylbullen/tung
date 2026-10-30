package io.github.kanylbullen.tung.core.ai

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.catalog.EquipmentFilter
import io.github.kanylbullen.tung.core.isLogged
import io.github.kanylbullen.tung.core.progress.dateOf
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything the in-app assistant sends a model about the user: training data
 * and settings, nothing else (`docs/architecture.md`, "AI features"). The
 * shape has no field for an email, a name, a device id, a server address or a
 * free-text note, so none can be sent by accident: the builder
 * ([buildAiContext]) takes core's plain shapes, and whatever else the caller
 * holds never reaches it.
 *
 * Snake case on the wire, like the MCP read tools, so a model that knows one
 * knows the other. Weights are kilograms ([unit]).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AiContext(
    val unit: String = "kg",
    /** The user's local date, ISO (`2026-10-20`), and its ISO weekday (1 Monday … 7 Sunday). */
    val today: String,
    val weekday: Int,
    /** Finished workouts, newest first, at most [AI_HISTORY_WORKOUTS] within [AI_HISTORY_DAYS] days; only logged sets. */
    val history: List<AiWorkout>,
    val routines: List<AiRoutine>,
    val programs: List<AiProgram>,
    /** The core catalogue's keys: what a model may name an exercise by (`bench_press`). */
    @SerialName("catalogue_keys") val catalogueKeys: List<String>,
    /**
     * "My equipment" as [Equipment] keys, when the user has narrowed it; null
     * (and then left out of the JSON) when they have everything.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val equipment: List<String>? = null,
)

/** One finished workout: its local [date], the routine it was started from (if it still exists) and what was logged. */
@Serializable
data class AiWorkout(
    val date: String,
    val routine: String? = null,
    val exercises: List<AiExerciseLog>,
)

/** One exercise of a workout: catalogue [key] if it has one, its English or custom [name], its logged sets in order. */
@Serializable
data class AiExerciseLog(
    val key: String? = null,
    val name: String,
    val sets: List<AiSet>,
)

@Serializable
data class AiSet(
    @SerialName("weight_kg") val weightKg: Double,
    val reps: Int,
)

@Serializable
data class AiRoutine(
    val name: String,
    val exercises: List<AiRoutineExercise>,
)

@Serializable
data class AiRoutineExercise(
    val key: String? = null,
    val name: String,
    @SerialName("target_sets") val targetSets: Int,
    @SerialName("target_reps") val targetReps: Int? = null,
)

/**
 * A program: [mode] `fixed` (each day on its weekdays) or `rotating` (the days
 * in order), whether it is the [active] one, its days in order, and in a
 * rotating program the number of the day completed last.
 */
@Serializable
data class AiProgram(
    val name: String,
    val mode: String,
    val active: Boolean,
    val days: List<AiProgramDay>,
    @SerialName("last_completed_day") val lastCompletedDay: Int? = null,
)

/** Day [day] (1-based) of a program: the routine's name and, in fixed mode, its ISO weekdays. */
@Serializable
data class AiProgramDay(
    val day: Int,
    val routine: String,
    val weekdays: List<Int> = emptyList(),
)

/** How far back the history goes, in days, and how many workouts it carries at most. */
const val AI_HISTORY_DAYS = 90
const val AI_HISTORY_WORKOUTS = 30

/** One set of a finished workout as the caller has it; only a logged one ([isLogged]) is sent. */
data class AiHistorySet(val weightKg: Double, val reps: Int, val completed: Boolean)

/** One exercise of a finished workout as the caller has it. */
data class AiHistoryExercise(val key: String?, val name: String, val sets: List<AiHistorySet>)

/** A finished workout as the caller has it: when it started and, if any, the name of its routine. */
data class AiHistoryWorkout(val startedAtMillis: Long, val routineName: String?, val exercises: List<AiHistoryExercise>)

/**
 * Builds the context from plain shapes. History: workouts that started within
 * [AI_HISTORY_DAYS] days before [nowMillis], newest first, at most
 * [AI_HISTORY_WORKOUTS]; only logged sets, and exercises and workouts left
 * empty by that are dropped. Dates are local dates in [zone]. [equipment] is
 * sent only when it is narrower than everything.
 */
fun buildAiContext(
    nowMillis: Long,
    zone: TimeZone,
    history: List<AiHistoryWorkout>,
    routines: List<AiRoutine>,
    programs: List<AiProgram>,
    catalogueKeys: Collection<String>,
    /** "My equipment"; [EquipmentFilter.ALL] (or null) sends nothing. */
    equipment: Set<Equipment>? = null,
): AiContext {
    val today = dateOf(nowMillis, zone)
    val since = nowMillis - AI_HISTORY_DAYS * 86_400_000L
    val workouts = history
        .filter { it.startedAtMillis >= since && it.startedAtMillis <= nowMillis }
        .sortedByDescending { it.startedAtMillis }
        .mapNotNull { workout ->
            val exercises = workout.exercises.mapNotNull { exercise ->
                val sets = exercise.sets.filter { isLogged(it.completed, it.reps) }.map { AiSet(it.weightKg, it.reps) }
                if (sets.isEmpty()) null else AiExerciseLog(exercise.key, exercise.name, sets)
            }
            if (exercises.isEmpty()) {
                null
            } else {
                AiWorkout(dateOf(workout.startedAtMillis, zone).toString(), workout.routineName, exercises)
            }
        }
        .take(AI_HISTORY_WORKOUTS)
    return AiContext(
        today = today.toString(),
        weekday = today.dayOfWeek.isoDayNumber,
        history = workouts,
        routines = routines,
        programs = programs,
        catalogueKeys = catalogueKeys.sorted(),
        equipment = equipment?.takeIf { EquipmentFilter.isNarrowed(it) }?.let(EquipmentFilter::toKeys),
    )
}
