package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.MAX_REPS
import io.github.kanylbullen.tung.core.MAX_TARGET_SETS
import io.github.kanylbullen.tung.core.MIN_TARGET_SETS
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.core.StartedBy
import io.github.kanylbullen.tung.core.health.MAX_ACTIVE_CALORIES
import io.github.kanylbullen.tung.core.health.MAX_HEART_RATE
import io.github.kanylbullen.tung.core.health.MIN_HEART_RATE
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.Validation
import io.github.kanylbullen.tung.core.command.validate
import io.github.kanylbullen.tung.core.sync.SyncChanges

/** Names are not capped by the app; this only bounds what one row may store. */
const val SYNC_MAX_NAME = 500
private const val MAX_NOTE = 10_000
private const val MAX_WEEKDAY_MASK = 0b111_1111
/** A Health Connect record id is a UUID; this leaves room for any other provider's ids. */
private const val MAX_HEALTH_CONNECT_ID = 200
private const val MAX_LISTED_PROBLEMS = 50

/**
 * Checks every pushed row before anything is written, with the same rules as
 * a command (core's `validate`: weight 0–999 kg, reps 0–999, rest 0–3600 s,
 * a routine's target sets 1–20, names not blank) plus the shape a stored row
 * needs. A live row needs every non-null column; a tombstone (`deleted_at`
 * set) only its id and timestamps, because only those are written. Exercise
 * rows with a catalogue id are the user's copy and take only `rest_seconds`
 * from the client, so only that is checked.
 *
 * Throws 400 `invalid_rows` naming every offending row (the first
 * [MAX_LISTED_PROBLEMS]); nothing is written then.
 */
fun validateSync(cursor: Long, changes: SyncChanges, catalogueIds: Set<String>) {
    val problems = mutableListOf<String>()
    if (cursor < 0) problems += "cursor must be 0 or more"

    fun <R> table(name: String, rows: List<R>, id: (R) -> String, updatedAt: (R) -> Long, deletedAt: (R) -> Long?, live: Checks.(R) -> Unit) {
        val seen = HashSet<String>()
        rows.forEachIndexed { i, row ->
            val checks = Checks()
            val rowId = id(row)
            if (!isUuid(rowId)) checks.fail("id must be a lower-case UUID")
            if (!seen.add(rowId)) checks.fail("the id appears twice in $name")
            if (updatedAt(row) < 0) checks.fail("updated_at must be epoch milliseconds")
            val deleted = deletedAt(row)
            if (deleted != null && deleted < 0) checks.fail("deleted_at must be epoch milliseconds")
            if (deleted == null) checks.live(row)
            if (checks.reasons.isNotEmpty()) problems += "$name[$i] ($rowId): ${checks.reasons.joinToString(", ")}"
        }
    }

    table("exercises", changes.exercises, { it.id }, { it.updatedAt }, { it.deletedAt }) { row ->
        command(Command.SetRest(row.id, row.restSeconds))
        if (row.id in catalogueIds) return@table
        val name = required(row.name, "name")
        if (name != null) {
            if (name.trim().length > SYNC_MAX_NAME) fail("name can be at most $SYNC_MAX_NAME characters")
            command(Command.CreateExercise(name, MuscleGroup.FULLBODY, Equipment.OTHER))
        }
        required(row.muscleGroup, "muscle_group")?.let { key ->
            if (MuscleGroup.entries.none { it.key == key }) fail("muscle_group must be one of ${MuscleGroup.entries.joinToString { it.key }}")
        }
        required(row.equipment, "equipment")?.let { key ->
            if (Equipment.entries.none { it.key == key }) fail("equipment must be one of ${Equipment.entries.joinToString { it.key }}")
        }
        required(row.isCustom, "is_custom")
    }

    table("workouts", changes.workouts, { it.id }, { it.updatedAt }, { it.deletedAt }) { row ->
        val started = required(row.startedAt, "started_at")
        if (started != null && started < 0) fail("started_at must be epoch milliseconds")
        row.endedAt?.let { if (it < 0 || (started != null && it < started)) fail("ended_at must not be before started_at") }
        if ((row.note?.length ?: 0) > MAX_NOTE) fail("note can be at most $MAX_NOTE characters")
        uuidOrNull(row.routineId, "routine_id")
        uuidOrNull(row.programDayId, "program_day_id")
        row.startedBy?.let { if (it !in StartedBy.ALL) fail("started_by must be one of ${StartedBy.ALL.joinToString()} or null") }
        row.avgHeartRate?.let { if (it !in MIN_HEART_RATE..MAX_HEART_RATE) fail("avg_heart_rate must be $MIN_HEART_RATE-$MAX_HEART_RATE or null") }
        row.maxHeartRate?.let { if (it !in MIN_HEART_RATE..MAX_HEART_RATE) fail("max_heart_rate must be $MIN_HEART_RATE-$MAX_HEART_RATE or null") }
        row.activeCalories?.let { if (it !in 0..MAX_ACTIVE_CALORIES) fail("active_calories must be 0-$MAX_ACTIVE_CALORIES or null") }
        row.healthConnectId?.let { if (it.isBlank() || it.length > MAX_HEALTH_CONNECT_ID) fail("health_connect_id must be 1-$MAX_HEALTH_CONNECT_ID characters or null") }
    }

    table("workout_exercises", changes.workoutExercises, { it.id }, { it.updatedAt }, { it.deletedAt }) { row ->
        uuid(row.workoutId, "workout_id")
        uuid(row.exerciseId, "exercise_id")
        position(row.position)
    }

    table("sets", changes.sets, { it.id }, { it.updatedAt }, { it.deletedAt }) { row ->
        uuid(row.workoutExerciseId, "workout_exercise_id")
        position(row.position)
        required(row.weightKg, "weight_kg")?.let { command(Command.SetWeight(row.id, it)) }
        required(row.reps, "reps")?.let { command(Command.SetReps(row.id, it)) }
        required(row.completed, "completed")
    }

    table("routines", changes.routines, { it.id }, { it.updatedAt }, { it.deletedAt }) { row ->
        name(row.name) { Command.RenameRoutine(row.id, it) }
        row.lastUsedAt?.let { if (it < 0) fail("last_used_at must be epoch milliseconds") }
    }

    table("routine_exercises", changes.routineExercises, { it.id }, { it.updatedAt }, { it.deletedAt }) { row ->
        uuid(row.routineId, "routine_id")
        uuid(row.exerciseId, "exercise_id")
        position(row.position)
        required(row.targetSets, "target_sets")?.let {
            if (it !in MIN_TARGET_SETS..MAX_TARGET_SETS) fail("target_sets must be between $MIN_TARGET_SETS and $MAX_TARGET_SETS")
        }
        row.targetReps?.let { if (it !in 0..MAX_REPS) fail("target_reps must be between 0 and $MAX_REPS") }
    }

    table("programs", changes.programs, { it.id }, { it.updatedAt }, { it.deletedAt }) { row ->
        name(row.name) { Command.RenameProgram(row.id, it) }
        required(row.mode, "mode")?.let { key ->
            if (ProgramMode.entries.none { it.key == key }) fail("mode must be one of ${ProgramMode.entries.joinToString { it.key }}")
        }
        required(row.isActive, "is_active")
    }

    table("program_days", changes.programDays, { it.id }, { it.updatedAt }, { it.deletedAt }) { row ->
        uuid(row.programId, "program_id")
        uuid(row.routineId, "routine_id")
        position(row.position)
        required(row.weekdays, "weekdays")?.let { if (it !in 0..MAX_WEEKDAY_MASK) fail("weekdays must be a bitmask of ISO weekdays, 0 to $MAX_WEEKDAY_MASK") }
    }

    if (problems.isNotEmpty()) {
        val listed = problems.take(MAX_LISTED_PROBLEMS).joinToString("; ")
        val more = if (problems.size > MAX_LISTED_PROBLEMS) "; and ${problems.size - MAX_LISTED_PROBLEMS} more" else ""
        throw badRequest("Nothing was written. ${problems.size} invalid: $listed$more.", "invalid_rows")
    }
}

/** The reasons one row is invalid, collected rather than thrown so a client sees them all. */
class Checks {
    val reasons = mutableListOf<String>()

    fun fail(reason: String) {
        reasons += reason
    }

    fun <T> required(value: T?, field: String): T? {
        if (value == null) fail("$field is required on a row that is not deleted")
        return value
    }

    fun uuid(value: String?, field: String) {
        required(value, field)?.let { if (!isUuid(it)) fail("$field must be a lower-case UUID") }
    }

    fun uuidOrNull(value: String?, field: String) {
        if (value != null && !isUuid(value)) fail("$field must be a lower-case UUID or null")
    }

    fun position(value: Int?) {
        required(value, "position")?.let { if (it < 0) fail("position must be 0 or more") }
    }

    fun name(value: String?, command: (String) -> Command) {
        val name = required(value, "name") ?: return
        if (name.trim().length > SYNC_MAX_NAME) fail("name can be at most $SYNC_MAX_NAME characters")
        command(command(name))
    }

    fun command(command: Command) {
        val result = validate(command)
        if (result is Validation.Invalid) reasons += result.reasons
    }
}
