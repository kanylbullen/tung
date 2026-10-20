package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.core.CoreExercise
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.sync.SyncChanges
import io.github.kanylbullen.tung.core.sync.SyncExercise
import io.github.kanylbullen.tung.core.sync.SyncProgram
import io.github.kanylbullen.tung.core.sync.SyncProgramDay
import io.github.kanylbullen.tung.core.sync.SyncRequest
import io.github.kanylbullen.tung.core.sync.SyncResponse
import io.github.kanylbullen.tung.core.sync.SyncRoutine
import io.github.kanylbullen.tung.core.sync.SyncRoutineExercise
import io.github.kanylbullen.tung.core.sync.SyncSet
import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.github.kanylbullen.tung.core.sync.SyncWorkoutExercise
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types

/**
 * `POST /api/v1/sync`: row-level sync of the eight training-data tables.
 *
 * One transaction per request, under the user's lock ([lockUser]):
 * 1. every pushed row is written by the row rule: a live row is upserted when
 *    its `updated_at` is strictly newer than the stored one (or nothing is
 *    stored); a tombstone sets `deleted_at` and `updated_at` on a stored row
 *    that is older and keeps the row. A tombstone for a row the server never
 *    had is dropped: there is nothing to delete, and nobody else can have it.
 *    Parents go first (exercises, workouts, workout exercises, sets, routines,
 *    routine exercises, programs, program days); the foreign keys are deferred
 *    to commit anyway;
 * 2. a live child whose parent does not exist fails the request with 409
 *    `missing_parent` naming the rows (the deferred foreign keys would too, at
 *    commit, without the names);
 * 3. when programs were pushed, at most one stays active
 *    ([resolveActivePrograms]: the newest `updated_at` wins, the others are
 *    switched off with a server stamp and so come back to their device);
 * 4. the answer is every row of the user's with `seq > cursor`, plus the
 *    stored version of every pushed row (so a push that lost to a newer row
 *    brings that row back), and `cursor` = the highest `seq` in it.
 *
 * Catalogue rows (`user_id NULL`) are never written: every statement is
 * scoped to the caller's `user_id`. An exercise row with a catalogue id is the
 * user's copy, and takes its name and kind from the catalogue, only
 * `rest_seconds` from the client, as `PATCH /exercises/{id}` does.
 */
class SyncStore(private val db: Db, catalogue: List<CoreExercise>) {

    private val catalogue: Map<String, CoreExercise> = catalogue.associateBy { catalogExerciseId(it.key) }

    suspend fun sync(userId: String, request: SyncRequest): SyncResponse {
        validateSync(request.cursor, request.changes, catalogue.keys)
        val changes = request.changes.copy(exercises = request.changes.exercises.map(::asCatalogueCopy))
        return try {
            db.tx {
                lockUser(userId)
                val connection = connection.connection as Connection
                for (table in TABLES) table.write(connection, userId, changes)
                checkParents(connection, userId, changes)
                // Two devices may each have activated a program offline: keep one, and the loser's next pull corrects it.
                if (changes.programs.isNotEmpty()) {
                    val pushedActive = changes.programs.filter { it.deletedAt == null && it.isActive == true }.map { it.id }.toSet()
                    resolveActivePrograms(userId, pushedActive, System.currentTimeMillis())
                }
                read(connection, userId, request.cursor, changes)
            }
        } catch (e: SQLException) {
            // The deferred foreign keys, checked at commit: only reachable if checkParents missed a case.
            if (generateSequence<Throwable>(e) { it.cause }.any { it is SQLException && it.sqlState == FOREIGN_KEY_VIOLATION }) {
                throw conflict("missing_parent", "A row refers to a parent that does not exist: ${e.message}")
            }
            throw e
        }
    }

    private fun asCatalogueCopy(row: SyncExercise): SyncExercise {
        val entry = catalogue[row.id] ?: return row
        return row.copy(
            nameKey = entry.key,
            name = entry.name["en"] ?: entry.key,
            muscleGroup = entry.muscleGroup,
            equipment = entry.equipment,
            isCustom = false,
        )
    }

    private fun read(connection: Connection, userId: String, cursor: Long, pushed: SyncChanges): SyncResponse {
        var max = cursor
        fun <R : Any> rows(table: SyncTable<R>): List<R> {
            val (rows, seq) = table.read(connection, userId, cursor, table.rows(pushed).map(table.id))
            max = maxOf(max, seq)
            return rows
        }
        val changes = SyncChanges(
            exercises = rows(EXERCISES),
            workouts = rows(WORKOUTS),
            workoutExercises = rows(WORKOUT_EXERCISES),
            sets = rows(SETS),
            routines = rows(ROUTINES),
            routineExercises = rows(ROUTINE_EXERCISES),
            programs = rows(PROGRAMS),
            programDays = rows(PROGRAM_DAYS),
        )
        return SyncResponse(max, changes)
    }

    /** Live pushed children whose parent row, or whose exercise, is not there. */
    private fun checkParents(connection: Connection, userId: String, changes: SyncChanges) {
        val problems = mutableListOf<String>()
        fun parent(child: String, ids: List<String>, column: String, parent: String) {
            if (ids.isEmpty()) return
            problems += connection.query(
                """
                SELECT c.id, c.$column FROM $child c
                WHERE c.user_id = ? AND c.id = ANY(?)
                  AND NOT EXISTS (SELECT 1 FROM $parent p WHERE p.user_id = c.user_id AND p.id = c.$column)
                LIMIT $MAX_LISTED
                """.trimIndent(),
                userId, connection.textArray(ids),
            ) { "$child ${it.getString(1)}: $column ${it.getString(2)} is not in $parent" }
        }
        fun exercise(child: String, ids: List<String>) {
            if (ids.isEmpty()) return
            problems += connection.query(
                """
                SELECT c.id, c.exercise_id FROM $child c
                WHERE c.user_id = ? AND c.id = ANY(?) AND c.deleted_at IS NULL
                  AND NOT EXISTS (
                    SELECT 1 FROM exercises e WHERE e.id = c.exercise_id AND (e.user_id IS NULL OR e.user_id = c.user_id)
                  )
                LIMIT $MAX_LISTED
                """.trimIndent(),
                userId, connection.textArray(ids),
            ) { "$child ${it.getString(1)}: exercise_id ${it.getString(2)} is neither a catalogue exercise nor one of yours" }
        }
        val workoutExercises = changes.workoutExercises.map { it.id }
        val routineExercises = changes.routineExercises.map { it.id }
        val programDays = changes.programDays.map { it.id }
        parent("workout_exercises", workoutExercises, "workout_id", "workouts")
        exercise("workout_exercises", workoutExercises)
        parent("sets", changes.sets.map { it.id }, "workout_exercise_id", "workout_exercises")
        parent("routine_exercises", routineExercises, "routine_id", "routines")
        exercise("routine_exercises", routineExercises)
        parent("program_days", programDays, "program_id", "programs")
        parent("program_days", programDays, "routine_id", "routines")
        if (problems.isNotEmpty()) {
            throw conflict("missing_parent", "Nothing was written. ${problems.take(MAX_LISTED).joinToString("; ")}.")
        }
    }

    private companion object {
        const val FOREIGN_KEY_VIOLATION = "23503"
        const val MAX_LISTED = 50

        val EXERCISES = SyncTable<SyncExercise>(
            name = "exercises",
            conflict = "ON CONSTRAINT exercises_user_id_id_key",
            columns = listOf(
                "name_key" to Types.VARCHAR, "name" to Types.VARCHAR, "muscle_group" to Types.VARCHAR,
                "equipment" to Types.VARCHAR, "is_custom" to Types.BOOLEAN, "rest_seconds" to Types.INTEGER,
            ),
            rows = { it.exercises },
            id = { it.id }, updatedAt = { it.updatedAt }, deletedAt = { it.deletedAt },
            values = { listOf(it.nameKey, it.name?.trim(), it.muscleGroup, it.equipment, it.isCustom, it.restSeconds) },
            map = { r ->
                SyncExercise(
                    id = r.getString("id"),
                    nameKey = r.getString("name_key"),
                    name = r.getString("name"),
                    muscleGroup = r.getString("muscle_group"),
                    equipment = r.getString("equipment"),
                    isCustom = r.getBoolean("is_custom"),
                    restSeconds = r.intOrNull("rest_seconds"),
                    updatedAt = r.getLong("updated_at"),
                    deletedAt = r.longOrNull("deleted_at"),
                )
            },
        )

        val WORKOUTS = SyncTable<SyncWorkout>(
            name = "workouts",
            columns = listOf(
                "started_at" to Types.BIGINT, "ended_at" to Types.BIGINT, "note" to Types.VARCHAR,
                "routine_id" to Types.VARCHAR, "program_day_id" to Types.VARCHAR, "started_by" to Types.VARCHAR,
            ),
            // Set when the workout is made and never changed: a row from a client that does not know the field keeps it.
            keepWhenNull = setOf("started_by"),
            rows = { it.workouts },
            id = { it.id }, updatedAt = { it.updatedAt }, deletedAt = { it.deletedAt },
            values = { listOf(it.startedAt, it.endedAt, it.note, it.routineId, it.programDayId, it.startedBy) },
            map = { r ->
                SyncWorkout(
                    id = r.getString("id"),
                    startedAt = r.getLong("started_at"),
                    endedAt = r.longOrNull("ended_at"),
                    note = r.getString("note"),
                    routineId = r.getString("routine_id"),
                    programDayId = r.getString("program_day_id"),
                    updatedAt = r.getLong("updated_at"),
                    deletedAt = r.longOrNull("deleted_at"),
                    startedBy = r.getString("started_by"),
                )
            },
        )

        val WORKOUT_EXERCISES = SyncTable<SyncWorkoutExercise>(
            name = "workout_exercises",
            columns = listOf("workout_id" to Types.VARCHAR, "exercise_id" to Types.VARCHAR, "position" to Types.INTEGER),
            rows = { it.workoutExercises },
            id = { it.id }, updatedAt = { it.updatedAt }, deletedAt = { it.deletedAt },
            values = { listOf(it.workoutId, it.exerciseId, it.position) },
            map = { r ->
                SyncWorkoutExercise(
                    id = r.getString("id"),
                    workoutId = r.getString("workout_id"),
                    exerciseId = r.getString("exercise_id"),
                    position = r.getInt("position"),
                    updatedAt = r.getLong("updated_at"),
                    deletedAt = r.longOrNull("deleted_at"),
                )
            },
        )

        val SETS = SyncTable<SyncSet>(
            name = "sets",
            columns = listOf(
                "workout_exercise_id" to Types.VARCHAR, "position" to Types.INTEGER, "weight_kg" to Types.DOUBLE,
                "reps" to Types.INTEGER, "completed" to Types.BOOLEAN,
            ),
            rows = { it.sets },
            id = { it.id }, updatedAt = { it.updatedAt }, deletedAt = { it.deletedAt },
            values = { listOf(it.workoutExerciseId, it.position, it.weightKg, it.reps, it.completed) },
            map = { r ->
                SyncSet(
                    id = r.getString("id"),
                    workoutExerciseId = r.getString("workout_exercise_id"),
                    position = r.getInt("position"),
                    weightKg = r.getDouble("weight_kg"),
                    reps = r.getInt("reps"),
                    completed = r.getBoolean("completed"),
                    updatedAt = r.getLong("updated_at"),
                    deletedAt = r.longOrNull("deleted_at"),
                )
            },
        )

        val ROUTINES = SyncTable<SyncRoutine>(
            name = "routines",
            columns = listOf("name" to Types.VARCHAR, "last_used_at" to Types.BIGINT),
            rows = { it.routines },
            id = { it.id }, updatedAt = { it.updatedAt }, deletedAt = { it.deletedAt },
            values = { listOf(it.name?.trim(), it.lastUsedAt) },
            map = { r ->
                SyncRoutine(
                    id = r.getString("id"),
                    name = r.getString("name"),
                    lastUsedAt = r.longOrNull("last_used_at"),
                    updatedAt = r.getLong("updated_at"),
                    deletedAt = r.longOrNull("deleted_at"),
                )
            },
        )

        val ROUTINE_EXERCISES = SyncTable<SyncRoutineExercise>(
            name = "routine_exercises",
            columns = listOf(
                "routine_id" to Types.VARCHAR, "exercise_id" to Types.VARCHAR, "position" to Types.INTEGER,
                "target_sets" to Types.INTEGER, "target_reps" to Types.INTEGER,
            ),
            rows = { it.routineExercises },
            id = { it.id }, updatedAt = { it.updatedAt }, deletedAt = { it.deletedAt },
            values = { listOf(it.routineId, it.exerciseId, it.position, it.targetSets, it.targetReps) },
            map = { r ->
                SyncRoutineExercise(
                    id = r.getString("id"),
                    routineId = r.getString("routine_id"),
                    exerciseId = r.getString("exercise_id"),
                    position = r.getInt("position"),
                    targetSets = r.getInt("target_sets"),
                    targetReps = r.intOrNull("target_reps"),
                    updatedAt = r.getLong("updated_at"),
                    deletedAt = r.longOrNull("deleted_at"),
                )
            },
        )

        val PROGRAMS = SyncTable<SyncProgram>(
            name = "programs",
            columns = listOf("name" to Types.VARCHAR, "mode" to Types.VARCHAR, "is_active" to Types.BOOLEAN),
            rows = { it.programs },
            id = { it.id }, updatedAt = { it.updatedAt }, deletedAt = { it.deletedAt },
            values = { listOf(it.name?.trim(), it.mode, it.isActive) },
            map = { r ->
                SyncProgram(
                    id = r.getString("id"),
                    name = r.getString("name"),
                    mode = r.getString("mode"),
                    isActive = r.getBoolean("is_active"),
                    updatedAt = r.getLong("updated_at"),
                    deletedAt = r.longOrNull("deleted_at"),
                )
            },
        )

        val PROGRAM_DAYS = SyncTable<SyncProgramDay>(
            name = "program_days",
            columns = listOf(
                "program_id" to Types.VARCHAR, "routine_id" to Types.VARCHAR, "position" to Types.INTEGER, "weekdays" to Types.INTEGER,
            ),
            rows = { it.programDays },
            id = { it.id }, updatedAt = { it.updatedAt }, deletedAt = { it.deletedAt },
            values = { listOf(it.programId, it.routineId, it.position, it.weekdays) },
            map = { r ->
                SyncProgramDay(
                    id = r.getString("id"),
                    programId = r.getString("program_id"),
                    routineId = r.getString("routine_id"),
                    position = r.getInt("position"),
                    weekdays = r.getInt("weekdays"),
                    updatedAt = r.getLong("updated_at"),
                    deletedAt = r.longOrNull("deleted_at"),
                )
            },
        )

        /** In write order: parents before children. */
        val TABLES: List<SyncTable<*>> =
            listOf(EXERCISES, WORKOUTS, WORKOUT_EXERCISES, SETS, ROUTINES, ROUTINE_EXERCISES, PROGRAMS, PROGRAM_DAYS)
    }
}

/**
 * How one synced table is written and read in plain JDBC: [columns] are the
 * data columns between `(user_id, id)` and `(updated_at, deleted_at)`, with
 * their JDBC types for nulls, and [values] gives a row's values in that order.
 * A column in [keepWhenNull] keeps its stored value when a newer row carries
 * null for it (`COALESCE(EXCLUDED.c, stored.c)`); the row rule is unchanged.
 */
private class SyncTable<R : Any>(
    val name: String,
    val conflict: String = "(user_id, id)",
    val columns: List<Pair<String, Int>>,
    val keepWhenNull: Set<String> = emptySet(),
    val rows: (SyncChanges) -> List<R>,
    val id: (R) -> String,
    val updatedAt: (R) -> Long,
    val deletedAt: (R) -> Long?,
    val values: (R) -> List<Any?>,
    val map: (ResultSet) -> R,
) {
    private val names = columns.map { it.first }

    /** Newer wins: the update only happens when the stored row is strictly older. A live row clears deleted_at. */
    private val upsert = """
        INSERT INTO $name (user_id, id, ${names.joinToString()}, updated_at, deleted_at)
        VALUES (?, ?, ${names.joinToString { "?" }}, ?, NULL)
        ON CONFLICT $conflict DO UPDATE SET ${names.joinToString { if (it in keepWhenNull) "$it = COALESCE(EXCLUDED.$it, $name.$it)" else "$it = EXCLUDED.$it" }},
            updated_at = EXCLUDED.updated_at, deleted_at = NULL
        WHERE $name.updated_at < EXCLUDED.updated_at
    """.trimIndent()

    /** A tombstone keeps the row and its last values; only a stored row that is older takes it. */
    private val tombstone = "UPDATE $name SET deleted_at = ?, updated_at = ? WHERE user_id = ? AND id = ? AND updated_at < ?"

    private val select = """
        SELECT id, ${names.joinToString()}, updated_at, deleted_at, seq FROM $name
        WHERE user_id = ? AND (seq > ? OR id = ANY(?))
        ORDER BY seq
    """.trimIndent()

    fun write(connection: Connection, userId: String, changes: SyncChanges) {
        val (dead, live) = rows(changes).partition { deletedAt(it) != null }
        if (live.isNotEmpty()) {
            connection.prepareStatement(upsert).use { statement ->
                for (row in live) {
                    statement.setString(1, userId)
                    statement.setString(2, id(row))
                    values(row).forEachIndexed { i, value -> statement.bind(3 + i, value, columns[i].second) }
                    statement.setLong(3 + columns.size, updatedAt(row))
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
        if (dead.isNotEmpty()) {
            connection.prepareStatement(tombstone).use { statement ->
                for (row in dead) {
                    statement.setLong(1, deletedAt(row) ?: error("partitioned on deleted_at"))
                    statement.setLong(2, updatedAt(row))
                    statement.setString(3, userId)
                    statement.setString(4, id(row))
                    statement.setLong(5, updatedAt(row))
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    /** The user's rows changed after [cursor] plus the [pushed] ones as stored, and the highest seq among them. */
    fun read(connection: Connection, userId: String, cursor: Long, pushed: List<String>): Pair<List<R>, Long> {
        var max = 0L
        val rows = mutableListOf<R>()
        connection.prepareStatement(select).use { statement ->
            statement.fetchSize = 1_000
            statement.setString(1, userId)
            statement.setLong(2, cursor)
            statement.setArray(3, connection.textArray(pushed))
            statement.executeQuery().use { result ->
                while (result.next()) {
                    rows += map(result)
                    max = maxOf(max, result.getLong("seq"))
                }
            }
        }
        return rows to max
    }
}

private fun java.sql.PreparedStatement.bind(index: Int, value: Any?, sqlType: Int) {
    if (value == null) setNull(index, sqlType) else setObject(index, value, sqlType)
}

private fun Connection.textArray(values: List<String>): java.sql.Array = createArrayOf("text", values.toTypedArray())

private fun <T> Connection.query(sql: String, vararg params: Any, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { statement ->
        params.forEachIndexed { i, param ->
            when (param) {
                is String -> statement.setString(i + 1, param)
                is java.sql.Array -> statement.setArray(i + 1, param)
                else -> statement.setObject(i + 1, param)
            }
        }
        statement.executeQuery().use { result -> buildList { while (result.next()) add(map(result)) } }
    }

private fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }

private fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }
