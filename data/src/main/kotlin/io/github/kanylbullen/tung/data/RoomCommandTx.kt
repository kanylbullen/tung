package io.github.kanylbullen.tung.data

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.command.CommandExecutor
import io.github.kanylbullen.tung.core.command.CommandTx
import io.github.kanylbullen.tung.core.command.SetRow
import io.github.kanylbullen.tung.core.command.WorkoutExerciseRow
import io.github.kanylbullen.tung.core.command.WorkoutRow

/**
 * Core's [CommandTx] over Room, for the [CommandExecutor]: the DAO inside a
 * transaction the caller opened (`db.withTransaction`, which the DAO calls
 * join), stamping `updatedAt` with [clock] and `dirty` through the entities'
 * defaults and the DAO's updates, like every other write in `data/`.
 */
internal class RoomCommandTx(private val dao: WorkoutDao, private val clock: () -> Long) : CommandTx {

    override suspend fun workout(id: String): WorkoutRow? =
        dao.getWorkout(id)?.let { WorkoutRow(it.id, it.routineId, it.endedAt) }

    override suspend fun workoutExercise(id: String): WorkoutExerciseRow? = dao.getWorkoutExercise(id)?.toRow()

    override suspend fun workoutExercises(workoutId: String): List<WorkoutExerciseRow> =
        dao.getWorkoutExercises(workoutId).map { it.toRow() }

    override suspend fun set(id: String): SetRow? = dao.getSet(id)?.toRow()

    override suspend fun sets(workoutExerciseId: String): List<SetRow> = dao.getSets(workoutExerciseId).map { it.toRow() }

    override suspend fun previousSession(exerciseId: String, excludeWorkoutId: String): List<LoggedSet> =
        dao.previousSessionSets(exerciseId, excludeWorkoutId).map { it.toLogged() }

    override suspend fun routineTargetSets(workoutId: String, exerciseId: String): Int? = dao.routineTargetSets(workoutId, exerciseId)

    override suspend fun insertWorkoutExercise(row: WorkoutExerciseRow) =
        dao.insertWorkoutExercise(WorkoutExercise(row.id, row.workoutId, row.exerciseId, row.position, updatedAt = clock()))

    override suspend fun insertSet(row: SetRow) =
        dao.insertSet(SetEntry(row.id, row.workoutExerciseId, row.position, row.weightKg, row.reps, row.completed, updatedAt = clock()))

    override suspend fun tickSet(id: String, done: Boolean) = dao.setCompleted(id, done, clock())

    private fun WorkoutExercise.toRow() = WorkoutExerciseRow(id, workoutId, exerciseId, position)

    private fun SetEntry.toRow() = SetRow(id, workoutExerciseId, position, weightKg, reps, completed)
}
