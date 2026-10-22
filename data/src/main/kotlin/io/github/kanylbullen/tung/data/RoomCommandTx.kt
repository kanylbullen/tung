package io.github.kanylbullen.tung.data

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.command.CommandExecutor
import io.github.kanylbullen.tung.core.command.CommandTx
import io.github.kanylbullen.tung.core.command.ProgramDayRow
import io.github.kanylbullen.tung.core.command.RoutineExerciseRow
import io.github.kanylbullen.tung.core.command.RoutineRow
import io.github.kanylbullen.tung.core.command.SetRow
import io.github.kanylbullen.tung.core.command.WorkoutExerciseRow
import io.github.kanylbullen.tung.core.command.WorkoutRow
import io.github.kanylbullen.tung.core.sync.SyncTable

/**
 * Core's [CommandTx] over Room, for the [CommandExecutor]: the DAOs inside a
 * transaction the caller opened (`db.withTransaction`, which the DAO calls
 * join), stamping `updatedAt` with [clock] and `dirty` through the entities'
 * defaults and the DAO's updates, and a tombstone for every delete
 * ([recordDeletes]), like every other write in `data/`.
 */
internal class RoomCommandTx(private val db: TungDatabase, private val clock: () -> Long) : CommandTx {

    private val dao: WorkoutDao = db.workoutDao()
    private val routineDao: RoutineDao = db.routineDao()
    private val programDao: ProgramDao = db.programDao()

    override fun now(): Long = clock()

    override suspend fun workout(id: String): WorkoutRow? = dao.getWorkout(id)?.toRow()

    override suspend fun activeWorkout(): WorkoutRow? = dao.getActiveWorkout()?.toRow()

    override suspend fun workoutExercise(id: String): WorkoutExerciseRow? = dao.getWorkoutExercise(id)?.toRow()

    override suspend fun workoutExercises(workoutId: String): List<WorkoutExerciseRow> =
        dao.getWorkoutExercises(workoutId).map { it.toRow() }

    override suspend fun set(id: String): SetRow? = dao.getSet(id)?.toRow()

    override suspend fun sets(workoutExerciseId: String): List<SetRow> = dao.getSets(workoutExerciseId).map { it.toRow() }

    override suspend fun previousSession(exerciseId: String, excludeWorkoutId: String): List<LoggedSet> =
        dao.previousSessionSets(exerciseId, excludeWorkoutId).map { it.toLogged() }

    override suspend fun routineTargetSets(workoutId: String, exerciseId: String): Int? = dao.routineTargetSets(workoutId, exerciseId)

    override suspend fun routine(id: String): RoutineRow? = routineDao.getRoutine(id)?.let { RoutineRow(it.id, it.name, it.lastUsedAt) }

    override suspend fun routineExercises(routineId: String): List<RoutineExerciseRow> =
        routineDao.getRoutineExercises(routineId).map { RoutineExerciseRow(it.id, it.routineId, it.exerciseId, it.position, it.targetSets, it.targetReps) }

    override suspend fun programDay(id: String): ProgramDayRow? =
        programDao.getDay(id)?.let { ProgramDayRow(it.id, it.programId, it.routineId, it.position, it.weekdays) }

    override suspend fun insertWorkout(row: WorkoutRow) = dao.insertWorkout(
        Workout(
            id = row.id,
            startedAt = row.startedAt,
            endedAt = row.endedAt,
            routineId = row.routineId,
            updatedAt = clock(),
            programDayId = row.programDayId,
            startedBy = row.startedBy,
        ),
    )

    override suspend fun insertWorkoutExercise(row: WorkoutExerciseRow) =
        dao.insertWorkoutExercise(WorkoutExercise(row.id, row.workoutId, row.exerciseId, row.position, updatedAt = clock()))

    override suspend fun insertSet(row: SetRow) =
        dao.insertSet(SetEntry(row.id, row.workoutExerciseId, row.position, row.weightKg, row.reps, row.completed, updatedAt = clock()))

    override suspend fun endWorkout(id: String, endedAt: Long) = dao.setEndedAt(id, endedAt = endedAt, updatedAt = clock())

    override suspend fun tickSet(id: String, done: Boolean) = dao.setCompleted(id, done, clock())

    override suspend fun setWeight(id: String, weightKg: Double) = dao.updateWeight(id, weightKg, clock())

    override suspend fun setReps(id: String, reps: Int) = dao.updateReps(id, reps, clock())

    override suspend fun markRoutineUsed(routineId: String) {
        val now = clock()
        routineDao.setLastUsedAt(routineId, lastUsedAt = now, updatedAt = now)
    }

    override suspend fun deleteSet(id: String) {
        db.recordDeletes(SyncTable.SETS, listOf(id), clock())
        dao.deleteSet(id)
    }

    override suspend fun deleteWorkoutExercise(id: String) {
        val now = clock()
        db.recordDeletes(SyncTable.SETS, dao.setIds(id), now)
        db.recordDeletes(SyncTable.WORKOUT_EXERCISES, listOf(id), now)
        dao.deleteWorkoutExercise(id)
    }

    override suspend fun deleteWorkout(id: String) {
        val now = clock()
        db.recordDeletes(SyncTable.SETS, dao.setIdsOfWorkout(id), now)
        db.recordDeletes(SyncTable.WORKOUT_EXERCISES, dao.workoutExerciseIds(id), now)
        db.recordDeletes(SyncTable.WORKOUTS, listOf(id), now)
        dao.deleteWorkout(id)
    }

    private fun Workout.toRow() = WorkoutRow(id, startedAt, endedAt, routineId, programDayId, startedBy)

    private fun WorkoutExercise.toRow() = WorkoutExerciseRow(id, workoutId, exerciseId, position)

    private fun SetEntry.toRow() = SetRow(id, workoutExerciseId, position, weightKg, reps, completed)
}
