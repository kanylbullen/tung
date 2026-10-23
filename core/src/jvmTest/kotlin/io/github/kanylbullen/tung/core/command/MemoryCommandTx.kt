package io.github.kanylbullen.tung.core.command

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.ProgramMode

/** A [CommandTx] over lists, for testing the executor's rules without a database. */
class MemoryCommandTx : CommandTx {
    val workouts = mutableListOf<WorkoutRow>()
    val exercises = mutableListOf<WorkoutExerciseRow>()
    val allSets = mutableListOf<SetRow>()
    val routines = mutableListOf<RoutineRow>()
    val routineExerciseRows = mutableListOf<RoutineExerciseRow>()
    val programs = mutableListOf<ProgramRow>()
    val programDays = mutableListOf<ProgramDayRow>()

    /** Last session per exercise id, as a store would answer it. */
    val history = mutableMapOf<String, List<LoggedSet>>()

    /** Routine target sets per exercise id, for workouts started from a routine. */
    val targets = mutableMapOf<String, Int>()

    /** Every write, in order, so a test can assert nothing was written. */
    val writes = mutableListOf<String>()

    /** The store's clock. */
    var now = 1_000L

    private var nextId = 1

    fun addWorkout(id: String = "w${nextId++}", routineId: String? = null, startedAt: Long = now, endedAt: Long? = null): String {
        workouts += WorkoutRow(id, startedAt, endedAt, routineId, programDayId = null, startedBy = null)
        return id
    }

    fun addExercise(workoutId: String, exerciseId: String, id: String = "we${nextId++}"): String {
        exercises += WorkoutExerciseRow(id, workoutId, exerciseId, (exercises.filter { it.workoutId == workoutId }.maxOfOrNull { it.position } ?: -1) + 1)
        return id
    }

    fun addSet(workoutExerciseId: String, weightKg: Double, reps: Int, completed: Boolean = false, id: String = "s${nextId++}"): String {
        allSets += SetRow(id, workoutExerciseId, (allSets.filter { it.workoutExerciseId == workoutExerciseId }.maxOfOrNull { it.position } ?: -1) + 1, weightKg, reps, completed)
        return id
    }

    fun addRoutine(id: String = "r${nextId++}", name: String = "Push"): String {
        routines += RoutineRow(id, name, lastUsedAt = null)
        return id
    }

    fun addRoutineExercise(routineId: String, exerciseId: String, targetSets: Int, targetReps: Int? = null, id: String = "re${nextId++}"): String {
        val position = (routineExerciseRows.filter { it.routineId == routineId }.maxOfOrNull { it.position } ?: -1) + 1
        routineExerciseRows += RoutineExerciseRow(id, routineId, exerciseId, position, targetSets, targetReps)
        return id
    }

    fun addProgram(id: String = "p${nextId++}", name: String = "PPL", mode: ProgramMode = ProgramMode.ROTATING, active: Boolean = false): String {
        programs += ProgramRow(id, name, mode, active)
        return id
    }

    fun addProgramDay(routineId: String, programId: String = "p1", id: String = "d${nextId++}", weekdays: Int = 0): String {
        programDays += ProgramDayRow(id, programId, routineId, programDays.count { it.programId == programId }, weekdays)
        return id
    }

    override fun now(): Long = now

    override suspend fun workout(id: String): WorkoutRow? = workouts.firstOrNull { it.id == id }

    override suspend fun activeWorkout(): WorkoutRow? = workouts.filter { it.endedAt == null }.maxByOrNull { it.startedAt }

    override suspend fun workoutExercise(id: String): WorkoutExerciseRow? = exercises.firstOrNull { it.id == id }

    override suspend fun workoutExercises(workoutId: String): List<WorkoutExerciseRow> =
        exercises.filter { it.workoutId == workoutId }.sortedBy { it.position }

    override suspend fun set(id: String): SetRow? = allSets.firstOrNull { it.id == id }

    override suspend fun sets(workoutExerciseId: String): List<SetRow> =
        allSets.filter { it.workoutExerciseId == workoutExerciseId }.sortedBy { it.position }

    override suspend fun previousSession(exerciseId: String, excludeWorkoutId: String): List<LoggedSet> = history[exerciseId].orEmpty()

    override suspend fun routineTargetSets(workoutId: String, exerciseId: String): Int? =
        if (workouts.firstOrNull { it.id == workoutId }?.routineId != null) targets[exerciseId] else null

    override suspend fun routine(id: String): RoutineRow? = routines.firstOrNull { it.id == id }

    override suspend fun routineExercise(id: String): RoutineExerciseRow? = routineExerciseRows.firstOrNull { it.id == id }

    override suspend fun routineExercises(routineId: String): List<RoutineExerciseRow> =
        routineExerciseRows.filter { it.routineId == routineId }.sortedBy { it.position }

    override suspend fun program(id: String): ProgramRow? = programs.firstOrNull { it.id == id }

    override suspend fun activePrograms(): List<ProgramRow> = programs.filter { it.isActive }

    override suspend fun programDay(id: String): ProgramDayRow? = programDays.firstOrNull { it.id == id }

    override suspend fun programDays(programId: String): List<ProgramDayRow> =
        programDays.filter { it.programId == programId }.sortedBy { it.position }

    override suspend fun programDaysUsing(routineId: String): List<ProgramDayRow> =
        programDays.filter { it.routineId == routineId }.sortedBy { it.position }

    override suspend fun insertWorkout(row: WorkoutRow) {
        workouts += row
        writes += "insert workout ${row.id}"
    }

    override suspend fun insertWorkoutExercise(row: WorkoutExerciseRow) {
        exercises += row
        writes += "insert exercise ${row.id}"
    }

    override suspend fun insertSet(row: SetRow) {
        allSets += row
        writes += "insert set ${row.id}"
    }

    override suspend fun endWorkout(id: String, endedAt: Long) {
        val index = workouts.indexOfFirst { it.id == id }
        require(index >= 0) { "no workout $id" }
        workouts[index] = workouts[index].copy(endedAt = endedAt)
        writes += "end $id $endedAt"
    }

    override suspend fun tickSet(id: String, done: Boolean) = updateSet(id, "tick $id $done") { it.copy(completed = done) }

    override suspend fun setWeight(id: String, weightKg: Double) = updateSet(id, "weight $id $weightKg") { it.copy(weightKg = weightKg) }

    override suspend fun setReps(id: String, reps: Int) = updateSet(id, "reps $id $reps") { it.copy(reps = reps) }

    override suspend fun insertRoutine(row: RoutineRow) {
        routines += row
        writes += "insert routine ${row.id}"
    }

    override suspend fun insertRoutineExercise(row: RoutineExerciseRow) {
        routineExerciseRows += row
        writes += "insert routine exercise ${row.id}"
    }

    override suspend fun renameRoutine(id: String, name: String) = updateRoutine(id, "rename $id $name") { it.copy(name = name) }

    override suspend fun markRoutineUsed(routineId: String) = updateRoutine(routineId, "used $routineId") { it.copy(lastUsedAt = now) }

    override suspend fun setRoutineExercisePosition(id: String, position: Int) =
        updateRoutineExercise(id, "position $id $position") { it.copy(position = position) }

    override suspend fun setTargetSets(id: String, targetSets: Int) =
        updateRoutineExercise(id, "target sets $id $targetSets") { it.copy(targetSets = targetSets) }

    override suspend fun setTargetReps(id: String, targetReps: Int?) =
        updateRoutineExercise(id, "target reps $id $targetReps") { it.copy(targetReps = targetReps) }

    override suspend fun deleteRoutineExercise(id: String) {
        require(routineExerciseRows.removeAll { it.id == id }) { "no routine exercise $id" }
        writes += "delete routine exercise $id"
    }

    override suspend fun deleteRoutine(id: String) {
        check(routineExerciseRows.none { it.routineId == id }) { "routine $id still has exercises" }
        check(programDays.none { it.routineId == id }) { "routine $id is still used by a program day" }
        require(routines.removeAll { it.id == id }) { "no routine $id" }
        writes += "delete routine $id"
    }

    override suspend fun insertProgram(row: ProgramRow) {
        programs += row
        writes += "insert program ${row.id}"
    }

    override suspend fun insertProgramDay(row: ProgramDayRow) {
        programDays += row
        writes += "insert day ${row.id}"
    }

    override suspend fun renameProgram(id: String, name: String) = updateProgram(id, "rename $id $name") { it.copy(name = name) }

    override suspend fun setProgramMode(id: String, mode: ProgramMode) = updateProgram(id, "mode $id ${mode.key}") { it.copy(mode = mode) }

    override suspend fun setProgramActive(id: String, active: Boolean) = updateProgram(id, "active $id $active") { it.copy(isActive = active) }

    override suspend fun setProgramDayPosition(id: String, position: Int) =
        updateDay(id, "day position $id $position") { it.copy(position = position) }

    override suspend fun setProgramDayWeekdays(id: String, weekdays: Int) =
        updateDay(id, "weekdays $id $weekdays") { it.copy(weekdays = weekdays) }

    override suspend fun deleteProgramDay(id: String) {
        require(programDays.removeAll { it.id == id }) { "no program day $id" }
        writes += "delete day $id"
    }

    override suspend fun deleteProgram(id: String) {
        check(programDays.none { it.programId == id }) { "program $id still has days" }
        require(programs.removeAll { it.id == id }) { "no program $id" }
        writes += "delete program $id"
    }

    override suspend fun deleteSet(id: String) {
        require(allSets.removeAll { it.id == id }) { "no set $id" }
        writes += "delete set $id"
    }

    override suspend fun deleteWorkoutExercise(id: String) {
        allSets.removeAll { it.workoutExerciseId == id }
        require(exercises.removeAll { it.id == id }) { "no exercise $id" }
        writes += "delete exercise $id"
    }

    override suspend fun deleteWorkout(id: String) {
        val exerciseIds = exercises.filter { it.workoutId == id }.map { it.id }
        allSets.removeAll { it.workoutExerciseId in exerciseIds }
        exercises.removeAll { it.workoutId == id }
        require(workouts.removeAll { it.id == id }) { "no workout $id" }
        writes += "delete workout $id"
    }

    private fun updateSet(id: String, write: String, change: (SetRow) -> SetRow) = update(allSets, id, { it.id }, write, change)

    private fun updateRoutine(id: String, write: String, change: (RoutineRow) -> RoutineRow) = update(routines, id, { it.id }, write, change)

    private fun updateRoutineExercise(id: String, write: String, change: (RoutineExerciseRow) -> RoutineExerciseRow) =
        update(routineExerciseRows, id, { it.id }, write, change)

    private fun updateProgram(id: String, write: String, change: (ProgramRow) -> ProgramRow) = update(programs, id, { it.id }, write, change)

    private fun updateDay(id: String, write: String, change: (ProgramDayRow) -> ProgramDayRow) = update(programDays, id, { it.id }, write, change)

    private fun <T> update(rows: MutableList<T>, id: String, idOf: (T) -> String, write: String, change: (T) -> T) {
        val index = rows.indexOfFirst { idOf(it) == id }
        require(index >= 0) { "no row $id" }
        rows[index] = change(rows[index])
        writes += write
    }
}
