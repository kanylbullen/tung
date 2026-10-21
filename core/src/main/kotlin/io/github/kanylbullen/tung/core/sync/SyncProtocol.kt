package io.github.kanylbullen.tung.core.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The sync protocol between the app and tung-server (issue #25):
// `POST /api/v1/sync` with a [SyncRequest], answered with a [SyncResponse].
// Every row is the full row with snake_case fields exactly as the server's
// columns. A tombstone is a row with `deleted_at` set; its other fields are
// the last known values or null. A row is applied only when its `updated_at`
// is strictly newer than the stored one.

const val SYNC_PATH = "/api/v1/sync"

/**
 * The header a client sends with its sync requests: its own stable device id.
 * The server pushes a change to every other device of the user, never back
 * to the one that made it; a request without the header (a script, MCP)
 * counts as a client of its own.
 */
const val DEVICE_HEADER = "X-Tung-Device"

/** The most rows one request may push; the server answers 413 above it. */
const val SYNC_MAX_ROWS = 20_000

@Serializable
data class SyncRequest(
    /** The cursor from the previous response; 0 on the first sync. */
    val cursor: Long = 0,
    val changes: SyncChanges = SyncChanges(),
)

@Serializable
data class SyncResponse(
    /** The highest server `seq` included; send it as the next request's cursor. */
    val cursor: Long,
    val changes: SyncChanges,
)

@Serializable
data class SyncChanges(
    val exercises: List<SyncExercise> = emptyList(),
    val workouts: List<SyncWorkout> = emptyList(),
    @SerialName("workout_exercises") val workoutExercises: List<SyncWorkoutExercise> = emptyList(),
    val sets: List<SyncSet> = emptyList(),
    val routines: List<SyncRoutine> = emptyList(),
    @SerialName("routine_exercises") val routineExercises: List<SyncRoutineExercise> = emptyList(),
    val programs: List<SyncProgram> = emptyList(),
    @SerialName("program_days") val programDays: List<SyncProgramDay> = emptyList(),
) {
    fun isEmpty(): Boolean = size() == 0

    fun size(): Int =
        exercises.size + workouts.size + workoutExercises.size + sets.size +
            routines.size + routineExercises.size + programs.size + programDays.size
}

@Serializable
data class SyncExercise(
    val id: String,
    @SerialName("name_key") val nameKey: String? = null,
    val name: String? = null,
    @SerialName("muscle_group") val muscleGroup: String? = null,
    val equipment: String? = null,
    @SerialName("is_custom") val isCustom: Boolean? = null,
    @SerialName("rest_seconds") val restSeconds: Int? = null,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
)

@Serializable
data class SyncWorkout(
    val id: String,
    @SerialName("started_at") val startedAt: Long? = null,
    @SerialName("ended_at") val endedAt: Long? = null,
    val note: String? = null,
    @SerialName("routine_id") val routineId: String? = null,
    @SerialName("program_day_id") val programDayId: String? = null,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
    /**
     * Who started the workout, one of [io.github.kanylbullen.tung.core.StartedBy];
     * null when unknown (rows from before the field existed). Additive: last,
     * so positional constructor calls keep working, and older peers ignore it.
     */
    @SerialName("started_by") val startedBy: String? = null,
    /**
     * From Health Connect on the phone that finished the workout (issue #45):
     * average and maximum heart rate (bpm) and active kilocalories over the
     * workout, and the id of the exercise session written there, so no device
     * writes it a second time. All additive and nullable like [startedBy], and
     * like it a null from a peer never erases a stored value: a client that
     * does not know the fields, or has no Health Connect, sends null.
     */
    @SerialName("avg_heart_rate") val avgHeartRate: Int? = null,
    @SerialName("max_heart_rate") val maxHeartRate: Int? = null,
    @SerialName("active_calories") val activeCalories: Int? = null,
    @SerialName("health_connect_id") val healthConnectId: String? = null,
    /**
     * Where an imported workout came from (`jefit`) and which import run
     * wrote it (a UUID), so "new record" leaves it out and an import can be
     * undone (issue #5). Additive and nullable like [startedBy], set once by
     * the import, and a null from a peer never erases a stored value.
     */
    @SerialName("import_source") val importSource: String? = null,
    @SerialName("import_batch_id") val importBatchId: String? = null,
)

@Serializable
data class SyncWorkoutExercise(
    val id: String,
    @SerialName("workout_id") val workoutId: String? = null,
    @SerialName("exercise_id") val exerciseId: String? = null,
    val position: Int? = null,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
)

@Serializable
data class SyncSet(
    val id: String,
    @SerialName("workout_exercise_id") val workoutExerciseId: String? = null,
    val position: Int? = null,
    @SerialName("weight_kg") val weightKg: Double? = null,
    val reps: Int? = null,
    val completed: Boolean? = null,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
)

@Serializable
data class SyncRoutine(
    val id: String,
    val name: String? = null,
    @SerialName("last_used_at") val lastUsedAt: Long? = null,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
)

@Serializable
data class SyncRoutineExercise(
    val id: String,
    @SerialName("routine_id") val routineId: String? = null,
    @SerialName("exercise_id") val exerciseId: String? = null,
    val position: Int? = null,
    @SerialName("target_sets") val targetSets: Int? = null,
    @SerialName("target_reps") val targetReps: Int? = null,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
)

@Serializable
data class SyncProgram(
    val id: String,
    val name: String? = null,
    val mode: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
)

@Serializable
data class SyncProgramDay(
    val id: String,
    @SerialName("program_id") val programId: String? = null,
    @SerialName("routine_id") val routineId: String? = null,
    val position: Int? = null,
    val weekdays: Int? = null,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
)
