package io.github.kanylbullen.tung.sync

import io.github.kanylbullen.tung.core.sync.SyncExercise
import io.github.kanylbullen.tung.core.sync.SyncProgram
import io.github.kanylbullen.tung.core.sync.SyncProgramDay
import io.github.kanylbullen.tung.core.sync.SyncRoutine
import io.github.kanylbullen.tung.core.sync.SyncRoutineExercise
import io.github.kanylbullen.tung.core.sync.SyncSet
import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.github.kanylbullen.tung.core.sync.SyncWorkoutExercise
import io.github.kanylbullen.tung.data.Exercise
import io.github.kanylbullen.tung.data.Program
import io.github.kanylbullen.tung.data.ProgramDay
import io.github.kanylbullen.tung.data.Routine
import io.github.kanylbullen.tung.data.RoutineExercise
import io.github.kanylbullen.tung.data.SetEntry
import io.github.kanylbullen.tung.data.Workout
import io.github.kanylbullen.tung.data.WorkoutExercise

// Room entity -> wire row: every field, as the server's columns.

fun Exercise.toSync() = SyncExercise(id, nameKey, name, muscleGroup, equipment, isCustom, restSeconds, updatedAt)
fun Workout.toSync() = SyncWorkout(id, startedAt, endedAt, note, routineId, programDayId, updatedAt)
fun WorkoutExercise.toSync() = SyncWorkoutExercise(id, workoutId, exerciseId, position, updatedAt)
fun SetEntry.toSync() = SyncSet(id, workoutExerciseId, position, weightKg, reps, completed, updatedAt)
fun Routine.toSync() = SyncRoutine(id, name, lastUsedAt, updatedAt)
fun RoutineExercise.toSync() = SyncRoutineExercise(id, routineId, exerciseId, position, targetSets, targetReps, updatedAt)
fun Program.toSync() = SyncProgram(id, name, mode, isActive, updatedAt)
fun ProgramDay.toSync() = SyncProgramDay(id, programId, routineId, position, weekdays, updatedAt)

// Wire row -> Room entity, written as the server has it (not dirty). A field
// Room requires that the row leaves null keeps the local value; with no local
// row to fall back on the row cannot be stored and the result is null.
// Nullable columns take the row's value, null included: a live row is the
// full row.

fun SyncExercise.toEntity(local: Exercise?): Exercise? {
    return Exercise(
        id = id,
        name = name ?: local?.name ?: return null,
        nameKey = nameKey,
        muscleGroup = muscleGroup ?: local?.muscleGroup ?: return null,
        equipment = equipment ?: local?.equipment ?: return null,
        isCustom = isCustom ?: local?.isCustom ?: return null,
        updatedAt = updatedAt,
        restSeconds = restSeconds,
        dirty = false,
    )
}

fun SyncWorkout.toEntity(local: Workout?): Workout? {
    return Workout(
        id = id,
        startedAt = startedAt ?: local?.startedAt ?: return null,
        endedAt = endedAt,
        note = note,
        routineId = routineId,
        updatedAt = updatedAt,
        programDayId = programDayId,
        dirty = false,
    )
}

fun SyncWorkoutExercise.toEntity(local: WorkoutExercise?): WorkoutExercise? {
    return WorkoutExercise(
        id = id,
        workoutId = workoutId ?: local?.workoutId ?: return null,
        exerciseId = exerciseId ?: local?.exerciseId ?: return null,
        position = position ?: local?.position ?: return null,
        updatedAt = updatedAt,
        dirty = false,
    )
}

fun SyncSet.toEntity(local: SetEntry?): SetEntry? {
    return SetEntry(
        id = id,
        workoutExerciseId = workoutExerciseId ?: local?.workoutExerciseId ?: return null,
        position = position ?: local?.position ?: return null,
        weightKg = weightKg ?: local?.weightKg ?: return null,
        reps = reps ?: local?.reps ?: return null,
        completed = completed ?: local?.completed ?: return null,
        updatedAt = updatedAt,
        dirty = false,
    )
}

fun SyncRoutine.toEntity(local: Routine?): Routine? {
    return Routine(
        id = id,
        name = name ?: local?.name ?: return null,
        updatedAt = updatedAt,
        lastUsedAt = lastUsedAt,
        dirty = false,
    )
}

fun SyncRoutineExercise.toEntity(local: RoutineExercise?): RoutineExercise? {
    return RoutineExercise(
        id = id,
        routineId = routineId ?: local?.routineId ?: return null,
        exerciseId = exerciseId ?: local?.exerciseId ?: return null,
        position = position ?: local?.position ?: return null,
        targetSets = targetSets ?: local?.targetSets ?: return null,
        targetReps = targetReps,
        updatedAt = updatedAt,
        dirty = false,
    )
}

fun SyncProgram.toEntity(local: Program?): Program? {
    return Program(
        id = id,
        name = name ?: local?.name ?: return null,
        mode = mode ?: local?.mode ?: return null,
        isActive = isActive ?: local?.isActive ?: return null,
        updatedAt = updatedAt,
        dirty = false,
    )
}

fun SyncProgramDay.toEntity(local: ProgramDay?): ProgramDay? {
    return ProgramDay(
        id = id,
        programId = programId ?: local?.programId ?: return null,
        routineId = routineId ?: local?.routineId ?: return null,
        position = position ?: local?.position ?: return null,
        weekdays = weekdays ?: local?.weekdays ?: return null,
        updatedAt = updatedAt,
        dirty = false,
    )
}
