package io.github.kanylbullen.tung.core.sync

/**
 * The eight synced tables by their wire key (the list names in [SyncChanges]),
 * declared parents first: a row's foreign keys only point at tables above it.
 * [SyncPlanner] sends in this order and the app applies in it.
 */
enum class SyncTable(val key: String) {
    EXERCISES("exercises"),
    WORKOUTS("workouts"),
    ROUTINES("routines"),
    PROGRAMS("programs"),
    WORKOUT_EXERCISES("workout_exercises"),
    ROUTINE_EXERCISES("routine_exercises"),
    PROGRAM_DAYS("program_days"),
    SETS("sets"),
    ;

    companion object {
        fun fromKey(key: String): SyncTable? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One row of any table with the fields sync decides on. [row] is the
 * protocol type itself ([SyncExercise], [SyncSet], …), carried along untouched.
 */
data class SyncEntry(
    val table: SyncTable,
    val id: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    val row: Any,
) {
    val isTombstone: Boolean get() = deletedAt != null
}

/** Every row of these changes as an entry, parents first ([SyncTable] order). */
fun SyncChanges.entries(): List<SyncEntry> = buildList {
    exercises.forEach { add(SyncEntry(SyncTable.EXERCISES, it.id, it.updatedAt, it.deletedAt, it)) }
    workouts.forEach { add(SyncEntry(SyncTable.WORKOUTS, it.id, it.updatedAt, it.deletedAt, it)) }
    routines.forEach { add(SyncEntry(SyncTable.ROUTINES, it.id, it.updatedAt, it.deletedAt, it)) }
    programs.forEach { add(SyncEntry(SyncTable.PROGRAMS, it.id, it.updatedAt, it.deletedAt, it)) }
    workoutExercises.forEach { add(SyncEntry(SyncTable.WORKOUT_EXERCISES, it.id, it.updatedAt, it.deletedAt, it)) }
    routineExercises.forEach { add(SyncEntry(SyncTable.ROUTINE_EXERCISES, it.id, it.updatedAt, it.deletedAt, it)) }
    programDays.forEach { add(SyncEntry(SyncTable.PROGRAM_DAYS, it.id, it.updatedAt, it.deletedAt, it)) }
    sets.forEach { add(SyncEntry(SyncTable.SETS, it.id, it.updatedAt, it.deletedAt, it)) }
}

/** Puts entries back into one [SyncChanges], each in its table's list, keeping their order. */
fun List<SyncEntry>.toChanges(): SyncChanges {
    val byTable = groupBy { it.table }
    fun <T> rows(table: SyncTable): List<T> {
        @Suppress("UNCHECKED_CAST")
        return byTable[table].orEmpty().map { it.row as T }
    }
    return SyncChanges(
        exercises = rows(SyncTable.EXERCISES),
        workouts = rows(SyncTable.WORKOUTS),
        workoutExercises = rows(SyncTable.WORKOUT_EXERCISES),
        sets = rows(SyncTable.SETS),
        routines = rows(SyncTable.ROUTINES),
        routineExercises = rows(SyncTable.ROUTINE_EXERCISES),
        programs = rows(SyncTable.PROGRAMS),
        programDays = rows(SyncTable.PROGRAM_DAYS),
    )
}

/**
 * A tombstone row for [table]: [id], `updated_at` and `deleted_at` both
 * [deletedAt], every other field null. The app keeps no copy of a deleted
 * row, so "last known" is null for all of them, which the protocol allows.
 */
fun tombstoneEntry(table: SyncTable, id: String, deletedAt: Long): SyncEntry {
    val row: Any = when (table) {
        SyncTable.EXERCISES -> SyncExercise(id, null, null, null, null, null, null, deletedAt, deletedAt)
        SyncTable.WORKOUTS -> SyncWorkout(id, null, null, null, null, null, deletedAt, deletedAt)
        SyncTable.ROUTINES -> SyncRoutine(id, null, null, deletedAt, deletedAt)
        SyncTable.PROGRAMS -> SyncProgram(id, null, null, null, deletedAt, deletedAt)
        SyncTable.WORKOUT_EXERCISES -> SyncWorkoutExercise(id, null, null, null, deletedAt, deletedAt)
        SyncTable.ROUTINE_EXERCISES -> SyncRoutineExercise(id, null, null, null, null, null, deletedAt, deletedAt)
        SyncTable.PROGRAM_DAYS -> SyncProgramDay(id, null, null, null, null, deletedAt, deletedAt)
        SyncTable.SETS -> SyncSet(id, null, null, null, null, null, deletedAt, deletedAt)
    }
    return SyncEntry(table, id, deletedAt, deletedAt, row)
}
