package io.github.kanylbullen.tung.server.command

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.command.CommandExecutor
import io.github.kanylbullen.tung.core.command.CommandTx
import io.github.kanylbullen.tung.core.command.SetRow
import io.github.kanylbullen.tung.core.command.WorkoutExerciseRow
import io.github.kanylbullen.tung.core.command.WorkoutRow
import io.github.kanylbullen.tung.server.RoutineExercises
import io.github.kanylbullen.tung.server.Routines
import io.github.kanylbullen.tung.server.Sets
import io.github.kanylbullen.tung.server.WorkoutExercises
import io.github.kanylbullen.tung.server.WorkoutStore
import io.github.kanylbullen.tung.server.Workouts
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

/**
 * Core's [CommandTx] over the server's tables for one user, for the
 * [CommandExecutor]: the live rows (no tombstones) inside the transaction
 * [ServerCommandHandler] opened under the user's lock, writes stamped with
 * [now] (or `stored + 1` when that is later, the server's rule for its own
 * changes, [bumped]). The `seq` trigger does the rest.
 */
internal class ExposedCommandTx(
    private val tx: Transaction,
    private val userId: String,
    private val workouts: WorkoutStore,
    private val now: Long,
) : CommandTx {

    override suspend fun workout(id: String): WorkoutRow? =
        Workouts.selectAll()
            .where { (Workouts.userId eq userId) and (Workouts.id eq id) and Workouts.deletedAt.isNull() }
            .singleOrNull()
            ?.let { WorkoutRow(it[Workouts.id], it[Workouts.routineId], it[Workouts.endedAt]) }

    override suspend fun workoutExercise(id: String): WorkoutExerciseRow? =
        WorkoutExercises.selectAll()
            .where { (WorkoutExercises.userId eq userId) and (WorkoutExercises.id eq id) and WorkoutExercises.deletedAt.isNull() }
            .singleOrNull()
            ?.let { WorkoutExerciseRow(it[WorkoutExercises.id], it[WorkoutExercises.workoutId], it[WorkoutExercises.exerciseId], it[WorkoutExercises.position]) }

    override suspend fun workoutExercises(workoutId: String): List<WorkoutExerciseRow> =
        WorkoutExercises.selectAll()
            .where { (WorkoutExercises.userId eq userId) and (WorkoutExercises.workoutId eq workoutId) and WorkoutExercises.deletedAt.isNull() }
            .orderBy(WorkoutExercises.position, SortOrder.ASC)
            .map { WorkoutExerciseRow(it[WorkoutExercises.id], it[WorkoutExercises.workoutId], it[WorkoutExercises.exerciseId], it[WorkoutExercises.position]) }

    override suspend fun set(id: String): SetRow? =
        Sets.selectAll()
            .where { (Sets.userId eq userId) and (Sets.id eq id) and Sets.deletedAt.isNull() }
            .singleOrNull()
            ?.let { SetRow(it[Sets.id], it[Sets.workoutExerciseId], it[Sets.position], it[Sets.weightKg], it[Sets.reps], it[Sets.completed]) }

    override suspend fun sets(workoutExerciseId: String): List<SetRow> =
        Sets.selectAll()
            .where { (Sets.userId eq userId) and (Sets.workoutExerciseId eq workoutExerciseId) and Sets.deletedAt.isNull() }
            .orderBy(Sets.position, SortOrder.ASC)
            .map { SetRow(it[Sets.id], it[Sets.workoutExerciseId], it[Sets.position], it[Sets.weightKg], it[Sets.reps], it[Sets.completed]) }

    /** Last session's sets for [exerciseId], from `GET /exercises/{id}/last`'s query. */
    override suspend fun previousSession(exerciseId: String, excludeWorkoutId: String): List<LoggedSet> =
        with(workouts) { tx.lastSessionIn(userId, exerciseId, excludeWorkoutId) }.sets.map { LoggedSet(it.weightKg, it.reps) }

    /** How many sets the routine [workoutId] was started from asks for of [exerciseId]; null outside a routine. Read live, as in the app. */
    override suspend fun routineTargetSets(workoutId: String, exerciseId: String): Int? {
        val routineId = workout(workoutId)?.routineId ?: return null
        Routines.selectAll()
            .where { (Routines.userId eq userId) and (Routines.id eq routineId) and Routines.deletedAt.isNull() }
            .singleOrNull() ?: return null
        return RoutineExercises.selectAll()
            .where {
                (RoutineExercises.userId eq userId) and (RoutineExercises.routineId eq routineId) and
                    (RoutineExercises.exerciseId eq exerciseId) and RoutineExercises.deletedAt.isNull()
            }
            .maxOfOrNull { it[RoutineExercises.targetSets] }
    }

    override suspend fun insertWorkoutExercise(row: WorkoutExerciseRow) {
        WorkoutExercises.insert {
            it[userId] = this@ExposedCommandTx.userId
            it[WorkoutExercises.id] = row.id
            it[workoutId] = row.workoutId
            it[exerciseId] = row.exerciseId
            it[position] = row.position
            it[updatedAt] = now
        }
    }

    override suspend fun insertSet(row: SetRow) {
        Sets.insert {
            it[userId] = this@ExposedCommandTx.userId
            it[Sets.id] = row.id
            it[workoutExerciseId] = row.workoutExerciseId
            it[position] = row.position
            it[weightKg] = row.weightKg
            it[reps] = row.reps
            it[completed] = row.completed
            it[updatedAt] = now
        }
    }

    override suspend fun tickSet(id: String, done: Boolean) {
        Sets.update({ (Sets.userId eq userId) and (Sets.id eq id) }) {
            it[completed] = done
            it[updatedAt] = bumped(updatedAt, now)
        }
    }
}
