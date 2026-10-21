package io.github.kanylbullen.tung.server

import org.jetbrains.exposed.sql.Table

// Exposed DSL mappings of the Flyway schema (db/migration). Flyway owns the
// schema; these objects only describe it, nothing here creates tables. The
// `seq` column (V2) is left out on purpose: the trigger owns it, and only
// SyncStore reads it, in plain SQL.

object Users : Table("users") {
    val id = text("id")
    val email = text("email")
    /** V10: null for a user who signed in with Google only; such a user cannot log in with a password. */
    val passwordHash = text("password_hash").nullable()
    /** V10: the `sub` of the linked Google account, null when none is linked. */
    val googleSub = text("google_sub").nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object ApiTokens : Table("api_tokens") {
    val id = text("id")
    val userId = text("user_id")
    val name = text("name")
    val tokenHash = text("token_hash")
    val scope = text("scope")
    val createdAt = long("created_at")
    val lastUsedAt = long("last_used_at").nullable()
    val revokedAt = long("revoked_at").nullable()
    /** V5: set on tokens issued through OAuth; null never expires. */
    val expiresAt = long("expires_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Exercises : Table("exercises") {
    /** Null for the catalogue. */
    val userId = text("user_id").nullable()
    val id = text("id")
    val nameKey = text("name_key").nullable()
    val name = text("name")
    val muscleGroup = text("muscle_group")
    val equipment = text("equipment")
    val isCustom = bool("is_custom")
    val restSeconds = integer("rest_seconds").nullable()
    val updatedAt = long("updated_at")
    val deletedAt = long("deleted_at").nullable()
}

object Workouts : Table("workouts") {
    val userId = text("user_id")
    val id = text("id")
    val startedAt = long("started_at")
    val endedAt = long("ended_at").nullable()
    val note = text("note").nullable()
    val routineId = text("routine_id").nullable()
    val programDayId = text("program_day_id").nullable()
    val updatedAt = long("updated_at")
    val deletedAt = long("deleted_at").nullable()
    /** V7: one of core's `StartedBy` values, null when unknown; set at creation and never changed. */
    val startedBy = text("started_by").nullable()
    /** V9: from Health Connect on the phone (issue #45); synced, a null from a client keeps the stored value. */
    val avgHeartRate = integer("avg_heart_rate").nullable()
    val maxHeartRate = integer("max_heart_rate").nullable()
    val activeCalories = integer("active_calories").nullable()
    val healthConnectId = text("health_connect_id").nullable()
    override val primaryKey = PrimaryKey(userId, id)
}

object WorkoutExercises : Table("workout_exercises") {
    val userId = text("user_id")
    val id = text("id")
    val workoutId = text("workout_id")
    val exerciseId = text("exercise_id")
    val position = integer("position")
    val updatedAt = long("updated_at")
    val deletedAt = long("deleted_at").nullable()
    override val primaryKey = PrimaryKey(userId, id)
}

object Sets : Table("sets") {
    val userId = text("user_id")
    val id = text("id")
    val workoutExerciseId = text("workout_exercise_id")
    val position = integer("position")
    val weightKg = double("weight_kg")
    val reps = integer("reps")
    val completed = bool("completed")
    val updatedAt = long("updated_at")
    val deletedAt = long("deleted_at").nullable()
    override val primaryKey = PrimaryKey(userId, id)
}

object Routines : Table("routines") {
    val userId = text("user_id")
    val id = text("id")
    val name = text("name")
    val lastUsedAt = long("last_used_at").nullable()
    val updatedAt = long("updated_at")
    val deletedAt = long("deleted_at").nullable()
    override val primaryKey = PrimaryKey(userId, id)
}

object RoutineExercises : Table("routine_exercises") {
    val userId = text("user_id")
    val id = text("id")
    val routineId = text("routine_id")
    val exerciseId = text("exercise_id")
    val position = integer("position")
    val targetSets = integer("target_sets")
    val targetReps = integer("target_reps").nullable()
    val updatedAt = long("updated_at")
    val deletedAt = long("deleted_at").nullable()
    override val primaryKey = PrimaryKey(userId, id)
}

object Programs : Table("programs") {
    val userId = text("user_id")
    val id = text("id")
    val name = text("name")
    val mode = text("mode")
    val isActive = bool("is_active")
    val updatedAt = long("updated_at")
    val deletedAt = long("deleted_at").nullable()
    override val primaryKey = PrimaryKey(userId, id)
}

object ProgramDays : Table("program_days") {
    val userId = text("user_id")
    val id = text("id")
    val programId = text("program_id")
    val routineId = text("routine_id")
    val position = integer("position")
    val weekdays = integer("weekdays")
    val updatedAt = long("updated_at")
    val deletedAt = long("deleted_at").nullable()
    override val primaryKey = PrimaryKey(userId, id)
}

/** V6: where the server can wake a device (push, issue #34). Not synced. */
object PushEndpoints : Table("push_endpoints") {
    val id = text("id")
    val userId = text("user_id")
    val deviceId = text("device_id")
    val transport = text("transport")
    val endpoint = text("endpoint")
    val createdAt = long("created_at")
    val lastUsedAt = long("last_used_at").nullable()
    val revokedAt = long("revoked_at").nullable()
    override val primaryKey = PrimaryKey(userId, id)
}
