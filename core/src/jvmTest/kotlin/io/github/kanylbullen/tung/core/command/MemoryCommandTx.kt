package io.github.kanylbullen.tung.core.command

import io.github.kanylbullen.tung.core.LoggedSet

/** A [CommandTx] over lists, for testing the executor's rules without a database. */
class MemoryCommandTx : CommandTx {
    val workouts = mutableListOf<WorkoutRow>()
    val exercises = mutableListOf<WorkoutExerciseRow>()
    val allSets = mutableListOf<SetRow>()
    val routines = mutableListOf<RoutineRow>()
    val routineExerciseRows = mutableListOf<RoutineExerciseRow>()
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

    fun addProgramDay(routineId: String, programId: String = "p1", id: String = "d${nextId++}"): String {
        programDays += ProgramDayRow(id, programId, routineId, programDays.count { it.programId == programId }, weekdays = 0)
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

    override suspend fun routineExercises(routineId: String): List<RoutineExerciseRow> =
        routineExerciseRows.filter { it.routineId == routineId }.sortedBy { it.position }

    override suspend fun programDay(id: String): ProgramDayRow? = programDays.firstOrNull { it.id == id }

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

    override suspend fun markRoutineUsed(routineId: String) {
        val index = routines.indexOfFirst { it.id == routineId }
        require(index >= 0) { "no routine $routineId" }
        routines[index] = routines[index].copy(lastUsedAt = now)
        writes += "used $routineId"
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

    private fun updateSet(id: String, write: String, change: (SetRow) -> SetRow) {
        val index = allSets.indexOfFirst { it.id == id }
        require(index >= 0) { "no set $id" }
        allSets[index] = change(allSets[index])
        writes += write
    }
}
