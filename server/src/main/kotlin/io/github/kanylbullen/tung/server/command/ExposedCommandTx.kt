package io.github.kanylbullen.tung.server.command

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.core.command.CommandExecutor
import io.github.kanylbullen.tung.core.command.CommandTx
import io.github.kanylbullen.tung.core.command.ProgramDayRow
import io.github.kanylbullen.tung.core.command.ProgramRow
import io.github.kanylbullen.tung.core.command.RoutineExerciseRow
import io.github.kanylbullen.tung.core.command.RoutineRow
import io.github.kanylbullen.tung.core.command.SetRow
import io.github.kanylbullen.tung.core.command.WorkoutExerciseRow
import io.github.kanylbullen.tung.core.command.WorkoutRow
import io.github.kanylbullen.tung.server.ProgramDays
import io.github.kanylbullen.tung.server.Programs
import io.github.kanylbullen.tung.server.RoutineExercises
import io.github.kanylbullen.tung.server.Routines
import io.github.kanylbullen.tung.server.Sets
import io.github.kanylbullen.tung.server.WorkoutExercises
import io.github.kanylbullen.tung.server.WorkoutStore
import io.github.kanylbullen.tung.server.Workouts
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

/**
 * Core's [CommandTx] over the server's tables for one user, for the
 * [CommandExecutor]: the live rows (no tombstones) inside the transaction the
 * caller opened under the user's lock, writes stamped with [now] (or
 * `stored + 1` when that is later, the server's rule for its own changes,
 * [bumped]), deletes as tombstones. The `seq` trigger does the rest.
 */
internal class ExposedCommandTx(
    private val tx: Transaction,
    private val userId: String,
    private val workouts: WorkoutStore,
    private val now: Long,
) : CommandTx {

    override fun now(): Long = now

    override suspend fun workout(id: String): WorkoutRow? =
        Workouts.selectAll()
            .where { (Workouts.userId eq userId) and (Workouts.id eq id) and Workouts.deletedAt.isNull() }
            .singleOrNull()
            ?.toRow()

    override suspend fun activeWorkout(): WorkoutRow? =
        Workouts.selectAll()
            .where { (Workouts.userId eq userId) and Workouts.endedAt.isNull() and Workouts.deletedAt.isNull() }
            .orderBy(Workouts.startedAt, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.toRow()

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
        routine(routineId) ?: return null
        return RoutineExercises.selectAll()
            .where {
                (RoutineExercises.userId eq userId) and (RoutineExercises.routineId eq routineId) and
                    (RoutineExercises.exerciseId eq exerciseId) and RoutineExercises.deletedAt.isNull()
            }
            .maxOfOrNull { it[RoutineExercises.targetSets] }
    }

    // --- routines ---

    override suspend fun routine(id: String): RoutineRow? =
        Routines.selectAll()
            .where { (Routines.userId eq userId) and (Routines.id eq id) and Routines.deletedAt.isNull() }
            .singleOrNull()
            ?.let { RoutineRow(it[Routines.id], it[Routines.name], it[Routines.lastUsedAt]) }

    override suspend fun routineExercise(id: String): RoutineExerciseRow? =
        RoutineExercises.selectAll()
            .where { (RoutineExercises.userId eq userId) and (RoutineExercises.id eq id) and RoutineExercises.deletedAt.isNull() }
            .singleOrNull()
            ?.toRoutineExerciseRow()

    override suspend fun routineExercises(routineId: String): List<RoutineExerciseRow> =
        RoutineExercises.selectAll()
            .where { (RoutineExercises.userId eq userId) and (RoutineExercises.routineId eq routineId) and RoutineExercises.deletedAt.isNull() }
            .orderBy(RoutineExercises.position, SortOrder.ASC)
            .map { it.toRoutineExerciseRow() }

    override suspend fun insertRoutine(row: RoutineRow) {
        Routines.insert {
            it[userId] = this@ExposedCommandTx.userId
            it[Routines.id] = row.id
            it[name] = row.name
            it[lastUsedAt] = row.lastUsedAt
            it[updatedAt] = now
        }
    }

    override suspend fun insertRoutineExercise(row: RoutineExerciseRow) {
        RoutineExercises.insert {
            it[userId] = this@ExposedCommandTx.userId
            it[RoutineExercises.id] = row.id
            it[routineId] = row.routineId
            it[exerciseId] = row.exerciseId
            it[position] = row.position
            it[targetSets] = row.targetSets
            it[targetReps] = row.targetReps
            it[updatedAt] = now
        }
    }

    override suspend fun renameRoutine(id: String, name: String) {
        Routines.update({ (Routines.userId eq userId) and (Routines.id eq id) }) {
            it[Routines.name] = name
            it[updatedAt] = bumped(updatedAt, now)
        }
    }

    override suspend fun markRoutineUsed(routineId: String) {
        Routines.update({ (Routines.userId eq userId) and (Routines.id eq routineId) }) {
            it[lastUsedAt] = now
            it[updatedAt] = bumped(updatedAt, now)
        }
    }

    override suspend fun setRoutineExercisePosition(id: String, position: Int) {
        RoutineExercises.update({ (RoutineExercises.userId eq userId) and (RoutineExercises.id eq id) }) {
            it[RoutineExercises.position] = position
            it[updatedAt] = bumped(updatedAt, now)
        }
    }

    override suspend fun setTargetSets(id: String, targetSets: Int) {
        RoutineExercises.update({ (RoutineExercises.userId eq userId) and (RoutineExercises.id eq id) }) {
            it[RoutineExercises.targetSets] = targetSets
            it[updatedAt] = bumped(updatedAt, now)
        }
    }

    override suspend fun setTargetReps(id: String, targetReps: Int?) {
        RoutineExercises.update({ (RoutineExercises.userId eq userId) and (RoutineExercises.id eq id) }) {
            it[RoutineExercises.targetReps] = targetReps
            it[updatedAt] = bumped(updatedAt, now)
        }
    }

    override suspend fun deleteRoutineExercise(id: String) {
        tombstone(RoutineExercises, RoutineExercises.updatedAt, RoutineExercises.deletedAt, now) {
            (RoutineExercises.userId eq userId) and (RoutineExercises.id eq id)
        }
    }

    override suspend fun deleteRoutine(id: String) {
        tombstone(Routines, Routines.updatedAt, Routines.deletedAt, now) { (Routines.userId eq userId) and (Routines.id eq id) }
    }

    // --- programs ---

    override suspend fun program(id: String): ProgramRow? =
        Programs.selectAll()
            .where { (Programs.userId eq userId) and (Programs.id eq id) and Programs.deletedAt.isNull() }
            .singleOrNull()
            ?.toProgramRow()

    override suspend fun activePrograms(): List<ProgramRow> =
        Programs.selectAll()
            .where { (Programs.userId eq userId) and (Programs.isActive eq true) and Programs.deletedAt.isNull() }
            .orderBy(Programs.id, SortOrder.ASC)
            .map { it.toProgramRow() }

    override suspend fun programDay(id: String): ProgramDayRow? =
        ProgramDays.selectAll()
            .where { (ProgramDays.userId eq userId) and (ProgramDays.id eq id) and ProgramDays.deletedAt.isNull() }
            .singleOrNull()
            ?.toProgramDayRow()

    override suspend fun programDays(programId: String): List<ProgramDayRow> =
        ProgramDays.selectAll()
            .where { (ProgramDays.userId eq userId) and (ProgramDays.programId eq programId) and ProgramDays.deletedAt.isNull() }
            .orderBy(ProgramDays.position, SortOrder.ASC)
            .map { it.toProgramDayRow() }

    override suspend fun programDaysUsing(routineId: String): List<ProgramDayRow> =
        ProgramDays.selectAll()
            .where { (ProgramDays.userId eq userId) and (ProgramDays.routineId eq routineId) and ProgramDays.deletedAt.isNull() }
            .orderBy(ProgramDays.position, SortOrder.ASC)
            .map { it.toProgramDayRow() }

    override suspend fun insertProgram(row: ProgramRow) {
        Programs.insert {
            it[userId] = this@ExposedCommandTx.userId
            it[Programs.id] = row.id
            it[name] = row.name
            it[mode] = row.mode.key
            it[isActive] = row.isActive
            it[updatedAt] = now
        }
    }

    override suspend fun insertProgramDay(row: ProgramDayRow) {
        ProgramDays.insert {
            it[userId] = this@ExposedCommandTx.userId
            it[ProgramDays.id] = row.id
            it[programId] = row.programId
            it[routineId] = row.routineId
            it[position] = row.position
            it[weekdays] = row.weekdays
            it[updatedAt] = now
        }
    }

    override suspend fun renameProgram(id: String, name: String) {
        Programs.update({ (Programs.userId eq userId) and (Programs.id eq id) }) {
            it[Programs.name] = name
            it[updatedAt] = bumped(updatedAt, now)
        }
    }

    override suspend fun setProgramMode(id: String, mode: ProgramMode) {
        Programs.update({ (Programs.userId eq userId) and (Programs.id eq id) }) {
            it[Programs.mode] = mode.key
            it[updatedAt] = bumped(updatedAt, now)
        }
    }

    override suspend fun setProgramActive(id: String, active: Boolean) {
        Programs.update({ (Programs.userId eq userId) and (Programs.id eq id) }) {
            it[isActive] = active
            it[updatedAt] = bumped(updatedAt, now)
        }
    }

    override suspend fun setProgramDayPosition(id: String, position: Int) {
        ProgramDays.update({ (ProgramDays.userId eq userId) and (ProgramDays.id eq id) }) {
            it[ProgramDays.position] = position
            it[updatedAt] = bumped(updatedAt, now)
        }
    }

    override suspend fun setProgramDayWeekdays(id: String, weekdays: Int) {
        ProgramDays.update({ (ProgramDays.userId eq userId) and (ProgramDays.id eq id) }) {
            it[ProgramDays.weekdays] = weekdays
            it[updatedAt] = bumped(updatedAt, now)
        }
    }

    override suspend fun deleteProgramDay(id: String) {
        tombstone(ProgramDays, ProgramDays.updatedAt, ProgramDays.deletedAt, now) { (ProgramDays.userId eq userId) and (ProgramDays.id eq id) }
    }

    override suspend fun deleteProgram(id: String) {
        tombstone(Programs, Programs.updatedAt, Programs.deletedAt, now) { (Programs.userId eq userId) and (Programs.id eq id) }
    }

    // --- workouts ---

    override suspend fun insertWorkout(row: WorkoutRow) {
        Workouts.insert {
            it[userId] = this@ExposedCommandTx.userId
            it[Workouts.id] = row.id
            it[startedAt] = row.startedAt
            it[endedAt] = row.endedAt
            it[note] = null
            it[routineId] = row.routineId
            it[programDayId] = row.programDayId
            it[startedBy] = row.startedBy
            it[updatedAt] = now
        }
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

    override suspend fun endWorkout(id: String, endedAt: Long) {
        Workouts.update({ (Workouts.userId eq userId) and (Workouts.id eq id) }) {
            it[Workouts.endedAt] = endedAt
            it[updatedAt] = bumped(updatedAt, now)
        }
    }

    override suspend fun tickSet(id: String, done: Boolean) {
        Sets.update({ (Sets.userId eq userId) and (Sets.id eq id) }) {
            it[completed] = done
            it[updatedAt] = bumped(updatedAt, now)
        }
    }

    override suspend fun setWeight(id: String, weightKg: Double) {
        Sets.update({ (Sets.userId eq userId) and (Sets.id eq id) }) {
            it[Sets.weightKg] = weightKg
            it[updatedAt] = bumped(updatedAt, now)
        }
    }

    override suspend fun setReps(id: String, reps: Int) {
        Sets.update({ (Sets.userId eq userId) and (Sets.id eq id) }) {
            it[Sets.reps] = reps
            it[updatedAt] = bumped(updatedAt, now)
        }
    }

    override suspend fun deleteSet(id: String) {
        tombstone(Sets, Sets.updatedAt, Sets.deletedAt, now) { (Sets.userId eq userId) and (Sets.id eq id) }
    }

    override suspend fun deleteWorkoutExercise(id: String) {
        tombstone(Sets, Sets.updatedAt, Sets.deletedAt, now) { (Sets.userId eq userId) and (Sets.workoutExerciseId eq id) }
        tombstone(WorkoutExercises, WorkoutExercises.updatedAt, WorkoutExercises.deletedAt, now) {
            (WorkoutExercises.userId eq userId) and (WorkoutExercises.id eq id)
        }
    }

    override suspend fun deleteWorkout(id: String) {
        val exerciseIds = workoutExercises(id).map { it.id }
        if (exerciseIds.isNotEmpty()) {
            tombstone(Sets, Sets.updatedAt, Sets.deletedAt, now) { (Sets.userId eq userId) and (Sets.workoutExerciseId inList exerciseIds) }
        }
        tombstone(WorkoutExercises, WorkoutExercises.updatedAt, WorkoutExercises.deletedAt, now) {
            (WorkoutExercises.userId eq userId) and (WorkoutExercises.workoutId eq id)
        }
        tombstone(Workouts, Workouts.updatedAt, Workouts.deletedAt, now) { (Workouts.userId eq userId) and (Workouts.id eq id) }
    }

    private fun ResultRow.toRow() = WorkoutRow(
        this[Workouts.id], this[Workouts.startedAt], this[Workouts.endedAt],
        this[Workouts.routineId], this[Workouts.programDayId], this[Workouts.startedBy],
    )

    private fun ResultRow.toRoutineExerciseRow() = RoutineExerciseRow(
        this[RoutineExercises.id], this[RoutineExercises.routineId], this[RoutineExercises.exerciseId],
        this[RoutineExercises.position], this[RoutineExercises.targetSets], this[RoutineExercises.targetReps],
    )

    private fun ResultRow.toProgramRow() =
        ProgramRow(this[Programs.id], this[Programs.name], ProgramMode.fromKey(this[Programs.mode]), this[Programs.isActive])

    private fun ResultRow.toProgramDayRow() =
        ProgramDayRow(this[ProgramDays.id], this[ProgramDays.programId], this[ProgramDays.routineId], this[ProgramDays.position], this[ProgramDays.weekdays])
}
