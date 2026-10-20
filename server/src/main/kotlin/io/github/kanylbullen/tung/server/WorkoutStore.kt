package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.core.StartedBy
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.isLogged
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.upsert

private const val MAX_EXERCISES_PER_WORKOUT = 200
private const val MAX_SETS_PER_EXERCISE = 200
private const val MAX_NOTE = 10_000

/**
 * Workouts with their exercises and sets.
 *
 * The write rule, which sync will reuse: every row carries `updated_at`, and a
 * write replaces a stored row only when its `updated_at` is strictly newer.
 * Equal or older writes are ignored row by row, so replaying a request changes
 * nothing and a stale device cannot overwrite a newer edit. Deletes are
 * tombstones (`deleted_at`), never row deletes. Every write takes the user's
 * lock ([lockUser]) first, and the `seq` trigger stamps each written row, so
 * sync sees these writes like its own.
 */
class WorkoutStore(private val db: Db, private val exercises: ExerciseStore) {

    /** Newest first; [finishedOnly] leaves out the workout in progress (the MCP history tool). */
    suspend fun page(userId: String, limit: Int, before: Long?, finishedOnly: Boolean = false): WorkoutPage = db.tx {
        val rows = Workouts.selectAll()
            .where {
                var live = (Workouts.userId eq userId) and Workouts.deletedAt.isNull()
                if (finishedOnly) live = live and Workouts.endedAt.isNotNull()
                if (before != null) live and (Workouts.startedAt less before) else live
            }
            .orderBy(Workouts.startedAt, SortOrder.DESC)
            .limit(limit)
            .toList()
        WorkoutPage(nested(userId, rows), if (rows.size == limit) rows.last()[Workouts.startedAt] else null)
    }

    suspend fun all(userId: String): List<WorkoutDto> = db.tx {
        nested(
            userId,
            Workouts.selectAll()
                .where { (Workouts.userId eq userId) and Workouts.deletedAt.isNull() }
                .orderBy(Workouts.startedAt, SortOrder.DESC)
                .toList(),
        )
    }

    suspend fun get(userId: String, id: String): WorkoutDto = db.tx { liveWorkout(userId, id) ?: throw notFound("Workout") }

    /**
     * Full nested upsert of the workout [id], by the client's ids, row by row
     * under the updated_at rule. A missing `updated_at` means now. Exercises
     * and sets stored under this workout but absent from the body are
     * tombstoned when they are older than the body's workout `updated_at`: the
     * client saw them and removed them. Newer ones were written after the
     * client's copy and stay.
     */
    suspend fun put(userId: String, id: String, input: WorkoutInput): WorkoutDto {
        requireUuid(id, "id")
        if (input.id != null && input.id != id) throw badRequest("The id in the body (${input.id}) differs from the one in the path ($id).")
        validate(input)
        val now = System.currentTimeMillis()
        return db.tx {
            lockUser(userId)
            val wanted = input.exercises.map { it.exerciseId }.toSet()
            val unknown = wanted - with(exercises) { visibleIds(userId, wanted) }
            if (unknown.isNotEmpty()) throw badRequest("Unknown exercise id: ${unknown.joinToString()}.", "unknown_exercise")

            val workoutUpdatedAt = input.updatedAt ?: now
            Workouts.upsert(
                // started_by is set once: a body without it keeps the stored value. The Health Connect
                // columns (V9) arrive by sync only; a PUT never carries them and must not null them.
                onUpdateExclude = listOfNotNull(
                    Workouts.startedBy.takeIf { input.startedBy == null },
                    Workouts.avgHeartRate, Workouts.maxHeartRate, Workouts.activeCalories, Workouts.healthConnectId,
                ),
                where = { Workouts.updatedAt less workoutUpdatedAt },
            ) {
                it[Workouts.userId] = userId
                it[Workouts.id] = id
                it[startedAt] = input.startedAt
                it[endedAt] = input.endedAt
                it[note] = input.note
                it[routineId] = input.routineId
                it[programDayId] = input.programDayId
                it[startedBy] = input.startedBy
                it[updatedAt] = workoutUpdatedAt
                it[deletedAt] = null
            }
            for (exercise in input.exercises) {
                val exerciseUpdatedAt = exercise.updatedAt ?: now
                WorkoutExercises.upsert(where = { WorkoutExercises.updatedAt less exerciseUpdatedAt }) {
                    it[WorkoutExercises.userId] = userId
                    it[WorkoutExercises.id] = exercise.id
                    it[workoutId] = id
                    it[exerciseId] = exercise.exerciseId
                    it[position] = exercise.position
                    it[updatedAt] = exerciseUpdatedAt
                    it[deletedAt] = null
                }
                for (set in exercise.sets) {
                    val setUpdatedAt = set.updatedAt ?: now
                    Sets.upsert(where = { Sets.updatedAt less setUpdatedAt }) {
                        it[Sets.userId] = userId
                        it[Sets.id] = set.id
                        it[workoutExerciseId] = exercise.id
                        it[position] = set.position
                        it[weightKg] = set.weightKg
                        it[reps] = set.reps
                        it[completed] = set.completed
                        it[updatedAt] = setUpdatedAt
                        it[deletedAt] = null
                    }
                }
            }

            val keptExercises = input.exercises.map { it.id }
            WorkoutExercises.update({
                (WorkoutExercises.userId eq userId) and (WorkoutExercises.workoutId eq id) and
                    WorkoutExercises.deletedAt.isNull() and (WorkoutExercises.id notInList keptExercises) and
                    (WorkoutExercises.updatedAt less workoutUpdatedAt)
            }) {
                it[deletedAt] = workoutUpdatedAt
                it[updatedAt] = workoutUpdatedAt
            }
            val exerciseIdsOfWorkout = WorkoutExercises.selectAll()
                .where { (WorkoutExercises.userId eq userId) and (WorkoutExercises.workoutId eq id) }
                .map { it[WorkoutExercises.id] }
            val keptSets = input.exercises.flatMap { e -> e.sets.map { it.id } }
            if (exerciseIdsOfWorkout.isNotEmpty()) {
                Sets.update({
                    (Sets.userId eq userId) and (Sets.workoutExerciseId inList exerciseIdsOfWorkout) and
                        Sets.deletedAt.isNull() and (Sets.id notInList keptSets) and (Sets.updatedAt less workoutUpdatedAt)
                }) {
                    it[deletedAt] = workoutUpdatedAt
                    it[updatedAt] = workoutUpdatedAt
                }
            }

            liveWorkout(userId, id)
                ?: throw conflict("workout_deleted", "This workout was deleted after the version you sent (a newer updated_at). Send a newer one to restore it.")
        }
    }

    /** Tombstones the workout with its exercises and sets. */
    suspend fun delete(userId: String, id: String) = db.tx {
        lockUser(userId)
        if (!deleteIn(userId, id)) throw notFound("Workout")
    }

    /** Tombstones the live workout [id] in the current transaction (the caller holds the user's lock); false when there is none. */
    fun Transaction.deleteIn(userId: String, id: String): Boolean {
        val row = liveRow(userId, id) ?: return false
        tombstone(userId, id, maxOf(System.currentTimeMillis(), row[Workouts.updatedAt] + 1))
        return true
    }

    /**
     * Finishes the workout as the app does: sets that are not logged (ticked
     * with reps > 0) are dropped, then exercises left without sets, and a
     * workout without one logged set is discarded instead of saved empty.
     * Finishing a finished workout returns it unchanged.
     */
    suspend fun finish(userId: String, id: String, input: FinishInput): FinishResult {
        input.endedAt?.let { validTimestamp(it, "ended_at") }
        return db.tx {
            lockUser(userId)
            finishIn(userId, id, input.endedAt) ?: throw notFound("Workout")
        }
    }

    /**
     * [finish] in the current transaction (the caller holds the user's lock),
     * shared with the command handler; null when there is no such workout.
     * Without [requestedEndedAt] the workout ends now, or at its start if
     * that lies ahead (a device clock that runs fast).
     */
    fun Transaction.finishIn(userId: String, id: String, requestedEndedAt: Long?): FinishResult? {
        val row = liveRow(userId, id) ?: return null
        if (row[Workouts.endedAt] != null) return FinishResult(liveWorkout(userId, id), discarded = false)
        val now = System.currentTimeMillis()
        val endedAt = requestedEndedAt ?: maxOf(now, row[Workouts.startedAt])
        if (endedAt < row[Workouts.startedAt]) throw badRequest("ended_at is before started_at.")
        val stamp = maxOf(now, row[Workouts.updatedAt] + 1)

        val workout = nested(userId, listOf(row)).single()
        var logged = 0
        for (exercise in workout.exercises) {
            val (keep, drop) = exercise.sets.partition { isLogged(it.completed, it.reps) }
            logged += keep.size
            tombstoneSets(userId, drop.map { it.id }, stamp)
            if (keep.isEmpty()) {
                WorkoutExercises.update({ (WorkoutExercises.userId eq userId) and (WorkoutExercises.id eq exercise.id) }) {
                    it[deletedAt] = stamp
                    it[updatedAt] = stamp
                }
            }
        }
        if (logged == 0) {
            tombstone(userId, id, stamp)
            return FinishResult(null, discarded = true)
        }
        Workouts.update({ (Workouts.userId eq userId) and (Workouts.id eq id) }) {
            it[Workouts.endedAt] = endedAt
            it[updatedAt] = stamp
        }
        return FinishResult(liveWorkout(userId, id), discarded = false)
    }

    /**
     * The app's most important query (WorkoutDao.previousSessionSets): the
     * completed sets of [exerciseId] from the most recent finished workout that
     * contains it, in order, optionally skipping [excludeWorkoutId] (the
     * workout in progress).
     */
    suspend fun lastSession(userId: String, exerciseId: String, excludeWorkoutId: String?): LastSession = db.tx {
        if (!with(exercises) { isVisible(userId, exerciseId) }) throw notFound("Exercise")
        lastSessionIn(userId, exerciseId, excludeWorkoutId)
    }

    /** [lastSession]'s query in the current transaction, without the visibility check; the command handler pre-fills from it. */
    fun Transaction.lastSessionIn(userId: String, exerciseId: String, excludeWorkoutId: String?): LastSession {
        val text = TextColumnType()
        var workoutId: String? = null
        var startedAt: Long? = null
        val sets = mutableListOf<LastSetDto>()
        exec(
            """
            WITH latest AS (
                SELECT we.id AS workout_exercise_id, w.id AS workout_id, w.started_at
                FROM workout_exercises we
                JOIN workouts w ON w.user_id = we.user_id AND w.id = we.workout_id
                WHERE we.user_id = ? AND we.exercise_id = ? AND we.deleted_at IS NULL
                  AND w.deleted_at IS NULL AND w.ended_at IS NOT NULL AND w.id <> ?
                ORDER BY w.started_at DESC, we.position ASC
                LIMIT 1
            )
            SELECT l.workout_id, l.started_at, s.id, s.position, s.weight_kg, s.reps
            FROM latest l
            LEFT JOIN sets s ON s.user_id = ? AND s.workout_exercise_id = l.workout_exercise_id
                AND s.deleted_at IS NULL AND s.completed
            ORDER BY s.position
            """.trimIndent(),
            listOf(text to userId, text to exerciseId, text to (excludeWorkoutId ?: ""), text to userId),
            explicitStatementType = StatementType.SELECT,
        ) { rs ->
            while (rs.next()) {
                workoutId = rs.getString(1)
                startedAt = rs.getLong(2)
                val setId = rs.getString(3) ?: continue
                sets += LastSetDto(setId, rs.getInt(4), rs.getDouble(5), rs.getInt(6))
            }
        }
        return LastSession(exerciseId, workoutId, startedAt, sets)
    }

    private fun validate(input: WorkoutInput) {
        validTimestamp(input.startedAt, "started_at")
        input.endedAt?.let { if (validTimestamp(it, "ended_at") < input.startedAt) throw badRequest("ended_at is before started_at.") }
        input.updatedAt?.let { validTimestamp(it, "updated_at") }
        input.routineId?.let { requireUuid(it, "routine_id") }
        input.programDayId?.let { requireUuid(it, "program_day_id") }
        input.startedBy?.let { if (it !in StartedBy.ALL) throw badRequest("started_by must be one of ${StartedBy.ALL.joinToString()}, got '$it'.") }
        if ((input.note?.length ?: 0) > MAX_NOTE) throw badRequest("note can be at most $MAX_NOTE characters.")
        if (input.exercises.size > MAX_EXERCISES_PER_WORKOUT) throw badRequest("A workout can have at most $MAX_EXERCISES_PER_WORKOUT exercises.")
        val seen = HashSet<String>()
        input.exercises.forEachIndexed { i, exercise ->
            val at = "exercises[$i]"
            requireUuid(exercise.id, "$at.id")
            requireUuid(exercise.exerciseId, "$at.exercise_id")
            if (!seen.add(exercise.id)) throw badRequest("$at.id ${exercise.id} appears twice.")
            if (exercise.position < 0) throw badRequest("$at.position must be 0 or more.")
            exercise.updatedAt?.let { validTimestamp(it, "$at.updated_at") }
            if (exercise.sets.size > MAX_SETS_PER_EXERCISE) throw badRequest("$at can have at most $MAX_SETS_PER_EXERCISE sets.")
            exercise.sets.forEachIndexed { j, set ->
                val setAt = "$at.sets[$j]"
                requireUuid(set.id, "$setAt.id")
                if (!seen.add(set.id)) throw badRequest("$setAt.id ${set.id} appears twice.")
                if (set.position < 0) throw badRequest("$setAt.position must be 0 or more.")
                requireValid(Command.SetWeight(set.id, set.weightKg), setAt)
                requireValid(Command.SetReps(set.id, set.reps), setAt)
                set.updatedAt?.let { validTimestamp(it, "$setAt.updated_at") }
            }
        }
    }

    private fun Transaction.liveRow(userId: String, id: String): ResultRow? =
        Workouts.selectAll()
            .where { (Workouts.userId eq userId) and (Workouts.id eq id) and Workouts.deletedAt.isNull() }
            .singleOrNull()

    fun Transaction.liveWorkout(userId: String, id: String): WorkoutDto? =
        liveRow(userId, id)?.let { nested(userId, listOf(it)).single() }

    private fun Transaction.tombstone(userId: String, id: String, stamp: Long) {
        Workouts.update({ (Workouts.userId eq userId) and (Workouts.id eq id) }) {
            it[deletedAt] = stamp
            it[updatedAt] = stamp
        }
        val exerciseIds = WorkoutExercises.selectAll()
            .where { (WorkoutExercises.userId eq userId) and (WorkoutExercises.workoutId eq id) }
            .map { it[WorkoutExercises.id] }
        WorkoutExercises.update({
            (WorkoutExercises.userId eq userId) and (WorkoutExercises.workoutId eq id) and WorkoutExercises.deletedAt.isNull()
        }) {
            it[deletedAt] = stamp
            it[updatedAt] = stamp
        }
        if (exerciseIds.isNotEmpty()) {
            Sets.update({ (Sets.userId eq userId) and (Sets.workoutExerciseId inList exerciseIds) and Sets.deletedAt.isNull() }) {
                it[deletedAt] = stamp
                it[updatedAt] = stamp
            }
        }
    }

    private fun Transaction.tombstoneSets(userId: String, ids: List<String>, stamp: Long) {
        if (ids.isEmpty()) return
        Sets.update({ (Sets.userId eq userId) and (Sets.id inList ids) }) {
            it[deletedAt] = stamp
            it[updatedAt] = stamp
        }
    }

    /** The live exercises and sets of [rows], nested and in position order, in three queries. */
    private fun Transaction.nested(userId: String, rows: List<ResultRow>): List<WorkoutDto> {
        if (rows.isEmpty()) return emptyList()
        val exerciseRows = WorkoutExercises.selectAll()
            .where {
                (WorkoutExercises.userId eq userId) and (WorkoutExercises.workoutId inList rows.map { it[Workouts.id] }) and
                    WorkoutExercises.deletedAt.isNull()
            }
            .orderBy(WorkoutExercises.position, SortOrder.ASC)
            .toList()
        val setsByExercise = if (exerciseRows.isEmpty()) {
            emptyMap()
        } else {
            Sets.selectAll()
                .where {
                    (Sets.userId eq userId) and (Sets.workoutExerciseId inList exerciseRows.map { it[WorkoutExercises.id] }) and
                        Sets.deletedAt.isNull()
                }
                .orderBy(Sets.position, SortOrder.ASC)
                .map {
                    it[Sets.workoutExerciseId] to SetDto(
                        id = it[Sets.id],
                        position = it[Sets.position],
                        weightKg = it[Sets.weightKg],
                        reps = it[Sets.reps],
                        completed = it[Sets.completed],
                        updatedAt = it[Sets.updatedAt],
                    )
                }
                .groupBy({ it.first }, { it.second })
        }
        val exercisesByWorkout = exerciseRows.groupBy({ it[WorkoutExercises.workoutId] }) {
            WorkoutExerciseDto(
                id = it[WorkoutExercises.id],
                exerciseId = it[WorkoutExercises.exerciseId],
                position = it[WorkoutExercises.position],
                updatedAt = it[WorkoutExercises.updatedAt],
                sets = setsByExercise[it[WorkoutExercises.id]].orEmpty(),
            )
        }
        return rows.map {
            WorkoutDto(
                id = it[Workouts.id],
                startedAt = it[Workouts.startedAt],
                endedAt = it[Workouts.endedAt],
                note = it[Workouts.note],
                routineId = it[Workouts.routineId],
                programDayId = it[Workouts.programDayId],
                updatedAt = it[Workouts.updatedAt],
                exercises = exercisesByWorkout[it[Workouts.id]].orEmpty(),
                startedBy = it[Workouts.startedBy],
                avgHeartRate = it[Workouts.avgHeartRate],
                maxHeartRate = it[Workouts.maxHeartRate],
                activeCalories = it[Workouts.activeCalories],
                healthConnectId = it[Workouts.healthConnectId],
            )
        }
    }
}
