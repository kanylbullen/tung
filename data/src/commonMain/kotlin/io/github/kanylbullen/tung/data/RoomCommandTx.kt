package io.github.kanylbullen.tung.data

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.CommandRules
import io.github.kanylbullen.tung.core.command.CommandStore
import io.github.kanylbullen.tung.core.command.CommandTx
import io.github.kanylbullen.tung.core.command.ExerciseRow
import io.github.kanylbullen.tung.core.command.ProgramDayRow
import io.github.kanylbullen.tung.core.command.ProgramRow
import io.github.kanylbullen.tung.core.command.RoutineExerciseRow
import io.github.kanylbullen.tung.core.command.RoutineRow
import io.github.kanylbullen.tung.core.command.SetRow
import io.github.kanylbullen.tung.core.command.WorkoutExerciseRow
import io.github.kanylbullen.tung.core.command.WorkoutRow
import io.github.kanylbullen.tung.core.sync.SyncTable

/** Core's [CommandStore] over Room: one `db.transaction` per command, over a [RoomCommandTx]. */
internal class RoomCommandStore(private val db: TungDatabase, private val clock: () -> Long) : CommandStore {
    override suspend fun transaction(block: suspend (CommandTx) -> CommandResult): CommandResult =
        db.transaction { block(RoomCommandTx(db, clock)) }
}

/**
 * Core's [CommandTx] over Room, for the [CommandRules]: the DAOs inside a
 * transaction the caller opened (`db.transaction`, which the DAO calls
 * join), stamping `updatedAt` with [clock] and `dirty` through the entities'
 * defaults and the DAO's updates, and a tombstone for every delete
 * ([recordDeletes]), like every other write in `data/`.
 */
internal class RoomCommandTx(private val db: TungDatabase, private val clock: () -> Long) : CommandTx {

    private val dao: WorkoutDao = db.workoutDao()
    private val exerciseDao: ExerciseDao = db.exerciseDao()
    private val routineDao: RoutineDao = db.routineDao()
    private val programDao: ProgramDao = db.programDao()

    override fun now(): Long = clock()

    // --- exercises ---

    override suspend fun exercise(id: String): ExerciseRow? = exerciseDao.getById(id)?.let {
        ExerciseRow(it.id, it.name, it.nameKey, MuscleGroup.fromKey(it.muscleGroup), Equipment.fromKey(it.equipment), it.isCustom, it.restSeconds)
    }

    override suspend fun insertExercise(row: ExerciseRow) = exerciseDao.insert(
        Exercise(
            id = row.id,
            name = row.name,
            nameKey = row.nameKey,
            muscleGroup = row.muscleGroup.key,
            equipment = row.equipment.key,
            isCustom = row.isCustom,
            updatedAt = clock(),
            restSeconds = row.restSeconds,
        ),
    )

    /** Insert-if-absent on the deterministic id, so picking the same extended entry again, or on another device, is the same row. */
    override suspend fun ensureExerciseRow(row: ExerciseRow) = exerciseDao.insertIfAbsent(
        Exercise(
            id = row.id,
            name = row.name,
            nameKey = row.nameKey,
            muscleGroup = row.muscleGroup.key,
            equipment = row.equipment.key,
            isCustom = row.isCustom,
            updatedAt = clock(),
            restSeconds = row.restSeconds,
        ),
    )

    /** An ordinary update, catalogue rows included (they are rows here), so `updatedAt` moves and the row goes up as the user's copy. */
    override suspend fun setRest(id: String, seconds: Int?) = exerciseDao.updateRestSeconds(id, seconds, clock())

    // --- workouts (reads) ---

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

    // --- routines ---

    override suspend fun routine(id: String): RoutineRow? = routineDao.getRoutine(id)?.let { RoutineRow(it.id, it.name, it.lastUsedAt) }

    override suspend fun routineExercise(id: String): RoutineExerciseRow? = routineDao.getRoutineExercise(id)?.toRow()

    override suspend fun routineExercises(routineId: String): List<RoutineExerciseRow> =
        routineDao.getRoutineExercises(routineId).map { it.toRow() }

    override suspend fun insertRoutine(row: RoutineRow) =
        routineDao.insertRoutine(Routine(id = row.id, name = row.name, updatedAt = clock(), lastUsedAt = row.lastUsedAt))

    override suspend fun insertRoutineExercise(row: RoutineExerciseRow) = routineDao.insertRoutineExercise(
        RoutineExercise(
            id = row.id,
            routineId = row.routineId,
            exerciseId = row.exerciseId,
            position = row.position,
            targetSets = row.targetSets,
            targetReps = row.targetReps,
            updatedAt = clock(),
        ),
    )

    override suspend fun renameRoutine(id: String, name: String) = routineDao.rename(id, name, clock())

    override suspend fun markRoutineUsed(routineId: String) {
        val now = clock()
        routineDao.setLastUsedAt(routineId, lastUsedAt = now, updatedAt = now)
    }

    override suspend fun setRoutineExercisePosition(id: String, position: Int) = routineDao.setPosition(id, position, clock())

    override suspend fun setTargetSets(id: String, targetSets: Int) = routineDao.setTargetSets(id, targetSets, clock())

    override suspend fun setTargetReps(id: String, targetReps: Int?) = routineDao.setTargetReps(id, targetReps, clock())

    override suspend fun deleteRoutineExercise(id: String) {
        db.recordDeletes(SyncTable.ROUTINE_EXERCISES, listOf(id), clock())
        routineDao.deleteRoutineExercise(id)
    }

    override suspend fun deleteRoutine(id: String) {
        db.recordDeletes(SyncTable.ROUTINES, listOf(id), clock())
        routineDao.deleteRoutine(id)
    }

    // --- programs ---

    override suspend fun program(id: String): ProgramRow? = programDao.getProgram(id)?.toRow()

    override suspend fun activePrograms(): List<ProgramRow> = programDao.getActivePrograms().map { it.toRow() }

    override suspend fun programDay(id: String): ProgramDayRow? = programDao.getDay(id)?.toRow()

    override suspend fun programDays(programId: String): List<ProgramDayRow> = programDao.getDays(programId).map { it.toRow() }

    override suspend fun programDaysUsing(routineId: String): List<ProgramDayRow> = programDao.getDaysUsing(routineId).map { it.toRow() }

    override suspend fun insertProgram(row: ProgramRow) =
        programDao.insertProgram(Program(id = row.id, name = row.name, mode = row.mode.key, isActive = row.isActive, updatedAt = clock()))

    override suspend fun insertProgramDay(row: ProgramDayRow) = programDao.insertDay(
        ProgramDay(id = row.id, programId = row.programId, routineId = row.routineId, position = row.position, weekdays = row.weekdays, updatedAt = clock()),
    )

    override suspend fun renameProgram(id: String, name: String) = programDao.rename(id, name, clock())

    override suspend fun setProgramMode(id: String, mode: ProgramMode) = programDao.setMode(id, mode.key, clock())

    override suspend fun setProgramActive(id: String, active: Boolean) = programDao.setActive(id, active, clock())

    override suspend fun setProgramDayPosition(id: String, position: Int) = programDao.setPosition(id, position, clock())

    override suspend fun setProgramDayWeekdays(id: String, weekdays: Int) = programDao.setWeekdays(id, weekdays, clock())

    override suspend fun deleteProgramDay(id: String) {
        db.recordDeletes(SyncTable.PROGRAM_DAYS, listOf(id), clock())
        programDao.deleteDay(id)
    }

    override suspend fun deleteProgram(id: String) {
        db.recordDeletes(SyncTable.PROGRAMS, listOf(id), clock())
        programDao.deleteProgram(id)
    }

    // --- workouts (writes) ---

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

    private fun RoutineExercise.toRow() = RoutineExerciseRow(id, routineId, exerciseId, position, targetSets, targetReps)

    private fun Program.toRow() = ProgramRow(id, name, ProgramMode.fromKey(mode), isActive)

    private fun ProgramDay.toRow() = ProgramDayRow(id, programId, routineId, position, weekdays)
}
