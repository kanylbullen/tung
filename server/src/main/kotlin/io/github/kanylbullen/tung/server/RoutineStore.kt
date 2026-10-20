package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.core.MAX_REPS
import io.github.kanylbullen.tung.core.MAX_TARGET_SETS
import io.github.kanylbullen.tung.core.MIN_TARGET_SETS
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.newId
import io.github.kanylbullen.tung.server.command.MAX_COMMAND_NAME
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.CustomFunction
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.LongColumnType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.longParam
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert

private const val MAX_EXERCISES_PER_ROUTINE = 200
private const val MAX_DAYS_PER_PROGRAM = 100
private const val MAX_WEEKDAY_MASK = 0b111_1111

/**
 * Routines and programs: the reads (export, REST, MCP) and the REST writes.
 *
 * The writes are the row rule, exactly as `PUT /workouts/{id}`
 * ([WorkoutStore.put]): every row (the routine and each routine exercise, the
 * program and each day) is upserted only when its `updated_at` is strictly
 * newer than the stored one, and children stored under the parent but
 * missing from the body are tombstoned only when they are older than the
 * body's parent `updated_at`. Every write takes the user's lock first. Deletes
 * are not here: the routes run core's `delete_routine` and `delete_program`
 * through the command handler, so the cascades are the app's. After every
 * write to programs, [resolveActivePrograms] keeps at most one active.
 */
class RoutineStore(private val db: Db, private val exercises: ExerciseStore) {

    /** The user's live routines with their live exercises in position order. */
    suspend fun routines(userId: String): List<RoutineDto> = db.tx { routineDtos(userId, null) }

    suspend fun routine(userId: String, id: String): RoutineDto = db.tx { routineDtos(userId, id).singleOrNull() ?: throw notFound("Routine") }

    /** The user's live programs with their live days in position order. */
    suspend fun programs(userId: String): List<ProgramDto> = db.tx { programDtos(userId, null) }

    suspend fun program(userId: String, id: String): ProgramDto = db.tx { programDtos(userId, id).singleOrNull() ?: throw notFound("Program") }

    /** `POST /routines`: a new routine under the body's id or a fresh one; 409 when the id is already taken. */
    suspend fun createRoutine(userId: String, input: RoutineInput): RoutineDto {
        val id = input.id ?: newId()
        validate(id, input)
        return db.tx {
            lockUser(userId)
            if (Routines.selectAll().where { (Routines.userId eq userId) and (Routines.id eq id) }.any()) {
                throw conflict("already_exists", "A routine with id $id exists (or existed). Use PUT /api/v1/routines/$id to change it.")
            }
            writeRoutine(userId, id, input)
        }
    }

    /**
     * `PUT /routines/{id}`: full nested upsert by the client's ids under the
     * row rule. A missing `updated_at` means now; a missing `last_used_at`
     * keeps the stored one (it is set when a workout is started from it).
     */
    suspend fun putRoutine(userId: String, id: String, input: RoutineInput): RoutineDto {
        requireUuid(id, "id")
        if (input.id != null && input.id != id) throw badRequest("The id in the body (${input.id}) differs from the one in the path ($id).")
        validate(id, input)
        return db.tx {
            lockUser(userId)
            writeRoutine(userId, id, input)
        }
    }

    /** `POST /programs`: a new program under the body's id or a fresh one; 409 when the id is already taken. */
    suspend fun createProgram(userId: String, input: ProgramInput): ProgramDto {
        val id = input.id ?: newId()
        validate(id, input)
        return db.tx {
            lockUser(userId)
            if (Programs.selectAll().where { (Programs.userId eq userId) and (Programs.id eq id) }.any()) {
                throw conflict("already_exists", "A program with id $id exists (or existed). Use PUT /api/v1/programs/$id to change it.")
            }
            writeProgram(userId, id, input)
        }
    }

    /** `PUT /programs/{id}`: full nested upsert of the program and its days under the row rule, then at most one active program. */
    suspend fun putProgram(userId: String, id: String, input: ProgramInput): ProgramDto {
        requireUuid(id, "id")
        if (input.id != null && input.id != id) throw badRequest("The id in the body (${input.id}) differs from the one in the path ($id).")
        validate(id, input)
        return db.tx {
            lockUser(userId)
            writeProgram(userId, id, input)
        }
    }

    private fun Transaction.writeRoutine(userId: String, id: String, input: RoutineInput): RoutineDto {
        val now = System.currentTimeMillis()
        val wanted = input.exercises.map { it.exerciseId }.toSet()
        val unknown = wanted - with(exercises) { visibleIds(userId, wanted) }
        if (unknown.isNotEmpty()) throw badRequest("Unknown exercise id: ${unknown.joinToString()}.", "unknown_exercise")

        val routineUpdatedAt = input.updatedAt ?: now
        Routines.upsert(
            onUpdateExclude = if (input.lastUsedAt == null) listOf(Routines.lastUsedAt) else null,
            where = { Routines.updatedAt less routineUpdatedAt },
        ) {
            it[Routines.userId] = userId
            it[Routines.id] = id
            it[name] = input.name.trim()
            it[lastUsedAt] = input.lastUsedAt
            it[updatedAt] = routineUpdatedAt
            it[deletedAt] = null
        }
        for (exercise in input.exercises) {
            val exerciseUpdatedAt = exercise.updatedAt ?: now
            RoutineExercises.upsert(where = { RoutineExercises.updatedAt less exerciseUpdatedAt }) {
                it[RoutineExercises.userId] = userId
                it[RoutineExercises.id] = exercise.id
                it[routineId] = id
                it[exerciseId] = exercise.exerciseId
                it[position] = exercise.position
                it[targetSets] = exercise.targetSets
                it[targetReps] = exercise.targetReps
                it[updatedAt] = exerciseUpdatedAt
                it[deletedAt] = null
            }
        }
        RoutineExercises.update({
            (RoutineExercises.userId eq userId) and (RoutineExercises.routineId eq id) and RoutineExercises.deletedAt.isNull() and
                (RoutineExercises.id notInList input.exercises.map { it.id }) and (RoutineExercises.updatedAt less routineUpdatedAt)
        }) {
            it[deletedAt] = routineUpdatedAt
            it[updatedAt] = routineUpdatedAt
        }
        return routineDtos(userId, id).singleOrNull()
            ?: throw conflict("routine_deleted", "This routine was deleted after the version you sent (a newer updated_at). Send a newer one to restore it.")
    }

    private fun Transaction.writeProgram(userId: String, id: String, input: ProgramInput): ProgramDto {
        val now = System.currentTimeMillis()
        val wanted = input.days.map { it.routineId }.toSet()
        if (wanted.isNotEmpty()) {
            val live = Routines.selectAll()
                .where { (Routines.userId eq userId) and (Routines.id inList wanted) and Routines.deletedAt.isNull() }
                .map { it[Routines.id] }
                .toSet()
            val unknown = wanted - live
            if (unknown.isNotEmpty()) throw badRequest("Unknown routine id: ${unknown.joinToString()}.", "unknown_routine")
        }

        val programUpdatedAt = input.updatedAt ?: now
        Programs.upsert(where = { Programs.updatedAt less programUpdatedAt }) {
            it[Programs.userId] = userId
            it[Programs.id] = id
            it[name] = input.name.trim()
            it[mode] = input.mode
            it[isActive] = input.isActive
            it[updatedAt] = programUpdatedAt
            it[deletedAt] = null
        }
        for (day in input.days) {
            val dayUpdatedAt = day.updatedAt ?: now
            ProgramDays.upsert(where = { ProgramDays.updatedAt less dayUpdatedAt }) {
                it[ProgramDays.userId] = userId
                it[ProgramDays.id] = day.id
                it[programId] = id
                it[routineId] = day.routineId
                it[position] = day.position
                it[weekdays] = day.weekdays
                it[updatedAt] = dayUpdatedAt
                it[deletedAt] = null
            }
        }
        ProgramDays.update({
            (ProgramDays.userId eq userId) and (ProgramDays.programId eq id) and ProgramDays.deletedAt.isNull() and
                (ProgramDays.id notInList input.days.map { it.id }) and (ProgramDays.updatedAt less programUpdatedAt)
        }) {
            it[deletedAt] = programUpdatedAt
            it[updatedAt] = programUpdatedAt
        }
        resolveActivePrograms(userId, if (input.isActive) setOf(id) else emptySet(), now)
        return programDtos(userId, id).singleOrNull()
            ?: throw conflict("program_deleted", "This program was deleted after the version you sent (a newer updated_at). Send a newer one to restore it.")
    }

    private fun validate(id: String, input: RoutineInput) {
        requireUuid(id, "id")
        validName(input.name, max = MAX_COMMAND_NAME)
        requireValid(Command.RenameRoutine(id, input.name))
        input.updatedAt?.let { validTimestamp(it, "updated_at") }
        input.lastUsedAt?.let { validTimestamp(it, "last_used_at") }
        if (input.exercises.size > MAX_EXERCISES_PER_ROUTINE) throw badRequest("A routine can have at most $MAX_EXERCISES_PER_ROUTINE exercises.")
        val seen = HashSet<String>()
        input.exercises.forEachIndexed { i, exercise ->
            val at = "exercises[$i]"
            requireUuid(exercise.id, "$at.id")
            requireUuid(exercise.exerciseId, "$at.exercise_id")
            if (!seen.add(exercise.id)) throw badRequest("$at.id ${exercise.id} appears twice.")
            if (exercise.position < 0) throw badRequest("$at.position must be 0 or more.")
            if (exercise.targetSets !in MIN_TARGET_SETS..MAX_TARGET_SETS) {
                throw badRequest("$at.target_sets must be between $MIN_TARGET_SETS and $MAX_TARGET_SETS.")
            }
            exercise.targetReps?.let { if (it !in 0..MAX_REPS) throw badRequest("$at.target_reps must be between 0 and $MAX_REPS.") }
            exercise.updatedAt?.let { validTimestamp(it, "$at.updated_at") }
        }
    }

    private fun validate(id: String, input: ProgramInput) {
        requireUuid(id, "id")
        validName(input.name, max = MAX_COMMAND_NAME)
        requireValid(Command.RenameProgram(id, input.name))
        if (ProgramMode.entries.none { it.key == input.mode }) {
            throw badRequest("mode must be one of ${ProgramMode.entries.joinToString { it.key }}, got '${input.mode}'.")
        }
        input.updatedAt?.let { validTimestamp(it, "updated_at") }
        if (input.days.size > MAX_DAYS_PER_PROGRAM) throw badRequest("A program can have at most $MAX_DAYS_PER_PROGRAM days.")
        val seen = HashSet<String>()
        input.days.forEachIndexed { i, day ->
            val at = "days[$i]"
            requireUuid(day.id, "$at.id")
            requireUuid(day.routineId, "$at.routine_id")
            if (!seen.add(day.id)) throw badRequest("$at.id ${day.id} appears twice.")
            if (day.position < 0) throw badRequest("$at.position must be 0 or more.")
            if (day.weekdays !in 0..MAX_WEEKDAY_MASK) throw badRequest("$at.weekdays must be a bitmask of ISO weekdays, 0 to $MAX_WEEKDAY_MASK.")
            day.updatedAt?.let { validTimestamp(it, "$at.updated_at") }
        }
    }

    /** The live routines of the user, or only [id], with their live exercises in position order. */
    private fun Transaction.routineDtos(userId: String, id: String?): List<RoutineDto> {
        val exercises = RoutineExercises.selectAll()
            .where {
                val live = (RoutineExercises.userId eq userId) and RoutineExercises.deletedAt.isNull()
                if (id == null) live else live and (RoutineExercises.routineId eq id)
            }
            .orderBy(RoutineExercises.position, SortOrder.ASC)
            .groupBy({ it[RoutineExercises.routineId] }) {
                RoutineExerciseDto(
                    id = it[RoutineExercises.id],
                    exerciseId = it[RoutineExercises.exerciseId],
                    position = it[RoutineExercises.position],
                    targetSets = it[RoutineExercises.targetSets],
                    targetReps = it[RoutineExercises.targetReps],
                    updatedAt = it[RoutineExercises.updatedAt],
                )
            }
        return Routines.selectAll()
            .where {
                val live = (Routines.userId eq userId) and Routines.deletedAt.isNull()
                if (id == null) live else live and (Routines.id eq id)
            }
            .orderBy(Routines.name, SortOrder.ASC)
            .map {
                RoutineDto(
                    id = it[Routines.id],
                    name = it[Routines.name],
                    lastUsedAt = it[Routines.lastUsedAt],
                    updatedAt = it[Routines.updatedAt],
                    exercises = exercises[it[Routines.id]].orEmpty(),
                )
            }
    }

    /** The live programs of the user, or only [id], with their live days in position order. */
    private fun Transaction.programDtos(userId: String, id: String?): List<ProgramDto> {
        val days = ProgramDays.selectAll()
            .where {
                val live = (ProgramDays.userId eq userId) and ProgramDays.deletedAt.isNull()
                if (id == null) live else live and (ProgramDays.programId eq id)
            }
            .orderBy(ProgramDays.position, SortOrder.ASC)
            .groupBy({ it[ProgramDays.programId] }) {
                ProgramDayDto(
                    id = it[ProgramDays.id],
                    routineId = it[ProgramDays.routineId],
                    position = it[ProgramDays.position],
                    weekdays = it[ProgramDays.weekdays],
                    updatedAt = it[ProgramDays.updatedAt],
                )
            }
        return Programs.selectAll()
            .where {
                val live = (Programs.userId eq userId) and Programs.deletedAt.isNull()
                if (id == null) live else live and (Programs.id eq id)
            }
            .orderBy(Programs.name, SortOrder.ASC)
            .map {
                ProgramDto(
                    id = it[Programs.id],
                    name = it[Programs.name],
                    mode = it[Programs.mode],
                    isActive = it[Programs.isActive],
                    updatedAt = it[Programs.updatedAt],
                    days = days[it[Programs.id]].orEmpty(),
                )
            }
    }
}

/**
 * At most one active program per user, across devices. Two devices can each
 * activate a different program offline and both push `is_active = true`;
 * after such a write (a sync push, `PUT`/`POST /programs`) this keeps the
 * active, live program with the newest `updated_at` (a tie goes to one of
 * [preferred], the rows just written, then to the lowest id, so the answer is
 * the same everywhere) and switches the others off with the server's stamp,
 * `GREATEST(now, updated_at + 1)`. The trigger gives them a new `seq`, so the
 * device that lost receives the correction on its next pull. The caller holds
 * the user's lock. Returns the ids it switched off.
 */
fun Transaction.resolveActivePrograms(userId: String, preferred: Set<String>, now: Long): List<String> {
    val active: List<ResultRow> = Programs.selectAll()
        .where { (Programs.userId eq userId) and (Programs.isActive eq true) and Programs.deletedAt.isNull() }
        .toList()
    if (active.size < 2) return emptyList()
    val keeper = active.sortedWith(
        compareByDescending<ResultRow> { it[Programs.updatedAt] }
            .thenByDescending { it[Programs.id] in preferred }
            .thenBy { it[Programs.id] },
    ).first()[Programs.id]
    val losers = active.map { it[Programs.id] } - keeper
    Programs.update({ (Programs.userId eq userId) and (Programs.id inList losers) }) {
        it[isActive] = false
        it[updatedAt] = serverStamp(updatedAt, now)
    }
    return losers
}

/** `GREATEST(now, updated_at + 1)`: the server's stamp for its own changes, so a write always moves a row forward. */
private fun serverStamp(updatedAt: Column<Long>, now: Long): Expression<Long> =
    CustomFunction("GREATEST", LongColumnType(), longParam(now), with(SqlExpressionBuilder) { updatedAt + 1L })
