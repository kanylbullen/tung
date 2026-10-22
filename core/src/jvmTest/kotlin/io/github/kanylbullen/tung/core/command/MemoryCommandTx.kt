package io.github.kanylbullen.tung.core.command

import io.github.kanylbullen.tung.core.LoggedSet

/** A [CommandTx] over lists, for testing the executor's rules without a database. */
class MemoryCommandTx : CommandTx {
    val workouts = mutableListOf<WorkoutRow>()
    val exercises = mutableListOf<WorkoutExerciseRow>()
    val allSets = mutableListOf<SetRow>()

    /** Last session per exercise id, as a store would answer it. */
    val history = mutableMapOf<String, List<LoggedSet>>()

    /** Routine target sets per exercise id, for workouts started from a routine. */
    val targets = mutableMapOf<String, Int>()

    /** Every write, in order, so a test can assert nothing was written. */
    val writes = mutableListOf<String>()

    private var nextId = 1

    fun addWorkout(id: String = "w${nextId++}", routineId: String? = null): String {
        workouts += WorkoutRow(id, routineId, endedAt = null)
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

    override suspend fun workout(id: String): WorkoutRow? = workouts.firstOrNull { it.id == id }

    override suspend fun workoutExercise(id: String): WorkoutExerciseRow? = exercises.firstOrNull { it.id == id }

    override suspend fun workoutExercises(workoutId: String): List<WorkoutExerciseRow> =
        exercises.filter { it.workoutId == workoutId }.sortedBy { it.position }

    override suspend fun set(id: String): SetRow? = allSets.firstOrNull { it.id == id }

    override suspend fun sets(workoutExerciseId: String): List<SetRow> =
        allSets.filter { it.workoutExerciseId == workoutExerciseId }.sortedBy { it.position }

    override suspend fun previousSession(exerciseId: String, excludeWorkoutId: String): List<LoggedSet> = history[exerciseId].orEmpty()

    override suspend fun routineTargetSets(workoutId: String, exerciseId: String): Int? =
        if (workouts.firstOrNull { it.id == workoutId }?.routineId != null) targets[exerciseId] else null

    override suspend fun insertWorkoutExercise(row: WorkoutExerciseRow) {
        exercises += row
        writes += "insert exercise ${row.id}"
    }

    override suspend fun insertSet(row: SetRow) {
        allSets += row
        writes += "insert set ${row.id}"
    }

    override suspend fun tickSet(id: String, done: Boolean) {
        val index = allSets.indexOfFirst { it.id == id }
        require(index >= 0) { "no set $id" }
        allSets[index] = allSets[index].copy(completed = done)
        writes += "tick $id $done"
    }
}
