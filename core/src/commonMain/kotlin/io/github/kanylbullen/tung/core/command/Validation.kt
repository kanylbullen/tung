package io.github.kanylbullen.tung.core.command

import io.github.kanylbullen.tung.core.ISO_WEEKDAYS
import io.github.kanylbullen.tung.core.MAX_REPS
import io.github.kanylbullen.tung.core.MAX_TARGET_SETS
import io.github.kanylbullen.tung.core.MAX_WEIGHT_KG
import io.github.kanylbullen.tung.core.MIN_TARGET_SETS
import io.github.kanylbullen.tung.core.command.Command.ActivateProgram
import io.github.kanylbullen.tung.core.command.Command.AddExercise
import io.github.kanylbullen.tung.core.command.Command.AddProgramDay
import io.github.kanylbullen.tung.core.command.Command.AddRoutineExercise
import io.github.kanylbullen.tung.core.command.Command.AddSet
import io.github.kanylbullen.tung.core.command.Command.AdjustTargetSets
import io.github.kanylbullen.tung.core.command.Command.ApplyPlan
import io.github.kanylbullen.tung.core.command.Command.CreateExercise
import io.github.kanylbullen.tung.core.command.Command.CreateProgram
import io.github.kanylbullen.tung.core.command.Command.CreateRoutine
import io.github.kanylbullen.tung.core.command.Command.DeactivateProgram
import io.github.kanylbullen.tung.core.command.Command.DeleteProgram
import io.github.kanylbullen.tung.core.command.Command.DeleteRoutine
import io.github.kanylbullen.tung.core.command.Command.DeleteRoutines
import io.github.kanylbullen.tung.core.command.Command.DeleteWorkout
import io.github.kanylbullen.tung.core.command.Command.FinishWorkout
import io.github.kanylbullen.tung.core.command.Command.LogSet
import io.github.kanylbullen.tung.core.command.Command.MoveProgramDay
import io.github.kanylbullen.tung.core.command.Command.MoveRoutineExercise
import io.github.kanylbullen.tung.core.command.Command.RemoveExercise
import io.github.kanylbullen.tung.core.command.Command.RemoveProgramDay
import io.github.kanylbullen.tung.core.command.Command.RemoveRoutineExercise
import io.github.kanylbullen.tung.core.command.Command.RemoveSet
import io.github.kanylbullen.tung.core.command.Command.RenameProgram
import io.github.kanylbullen.tung.core.command.Command.RenameRoutine
import io.github.kanylbullen.tung.core.command.Command.SaveRoutine
import io.github.kanylbullen.tung.core.command.Command.SaveRoutinePlan
import io.github.kanylbullen.tung.core.command.Command.SelectCurrent
import io.github.kanylbullen.tung.core.command.Command.SetProgramMode
import io.github.kanylbullen.tung.core.command.Command.SetReps
import io.github.kanylbullen.tung.core.command.Command.SetRest
import io.github.kanylbullen.tung.core.command.Command.SetTargetReps
import io.github.kanylbullen.tung.core.command.Command.SetWeight
import io.github.kanylbullen.tung.core.command.Command.StartRoutine
import io.github.kanylbullen.tung.core.command.Command.StartWorkout
import io.github.kanylbullen.tung.core.command.Command.TickSet
import io.github.kanylbullen.tung.core.command.Command.ToggleProgramWeekday

/** Fewest and most sets one exercise of a [WorkoutPlan] can have. */
const val MIN_PLANNED_SETS = 1
const val MAX_PLANNED_SETS = 50

/** Fewest and most sets one `add_exercise` or `add_set` can create. */
const val MIN_ADDED_SETS = 1
const val MAX_ADDED_SETS = 20

/** Most routines one `delete_routines` can name. */
const val MAX_DELETE_ROUTINES = 500

/** The longest rest timer a command may set, in seconds. The app's menu offers 0–300. */
const val MAX_REST_SECONDS = 3600

/** The outcome of [validate]. */
sealed interface Validation {
    data object Valid : Validation

    /** Every problem found, not only the first, so a caller can fix them in one go. */
    data class Invalid(val reasons: List<String>) : Validation
}

/**
 * Checks a command on its own, without the database: numbers in range
 * (weight 0..[MAX_WEIGHT_KG] kg, reps 0..[MAX_REPS], planned sets
 * [MIN_PLANNED_SETS]..[MAX_PLANNED_SETS], a routine's target sets
 * [MIN_TARGET_SETS]..[MAX_TARGET_SETS] and target reps 1..[MAX_REPS], sets
 * added at once [MIN_ADDED_SETS]..[MAX_ADDED_SETS], rest 0..[MAX_REST_SECONDS] s, weekday
 * 1..7, 1..[MAX_DELETE_ROUTINES] routines in `delete_routines`), names and ids
 * not blank, plans not empty. Whether the ids exist is the
 * handler's business. Pure, so the app, the server and a client checking
 * before it sends all agree.
 */
fun validate(command: Command): Validation {
    val reasons = mutableListOf<String>()
    fun id(value: String?, field: String) {
        if (value != null && value.isBlank()) reasons += "$field must not be blank"
    }
    fun name(value: String, field: String = "name") {
        if (value.isBlank()) reasons += "$field must not be blank"
    }
    fun weight(kg: Double, field: String = "weight") {
        if (kg.isNaN() || kg < 0.0 || kg > MAX_WEIGHT_KG) reasons += "$field must be between 0 and ${MAX_WEIGHT_KG.toInt()} kg"
    }
    fun reps(value: Int, field: String = "reps") {
        if (value !in 0..MAX_REPS) reasons += "$field must be between 0 and $MAX_REPS"
    }
    fun targetReps(value: Int?, field: String = "target reps") {
        if (value != null && value !in 1..MAX_REPS) reasons += "$field must be between 1 and $MAX_REPS"
    }
    fun addedSets(value: Int?, field: String) {
        if (value != null && value !in MIN_ADDED_SETS..MAX_ADDED_SETS) reasons += "$field must be between $MIN_ADDED_SETS and $MAX_ADDED_SETS"
    }
    fun ref(value: ExerciseRef, field: String = "exercise") {
        val text = when (value) {
            is ExerciseRef.ByKey -> value.nameKey
            is ExerciseRef.ById -> value.id
            is ExerciseRef.ByName -> value.text
            is ExerciseRef.Extended -> value.fedId
        }
        if (text.isBlank()) reasons += "$field must not be blank"
    }

    when (command) {
        is StartWorkout -> {
            id(command.routineId, "routineId")
            id(command.programDayId, "programDayId")
            if (command.programDayId != null && command.routineId == null) reasons += "programDayId needs a routineId"
        }
        is StartRoutine -> {
            id(command.routineId, "routineId")
            id(command.programDayId, "programDayId")
        }
        is AddExercise -> {
            id(command.workoutId, "workoutId")
            ref(command.exercise)
            addedSets(command.sets, "sets")
        }
        is RemoveExercise -> id(command.workoutExerciseId, "workoutExerciseId")
        is AddSet -> {
            id(command.workoutExerciseId, "workoutExerciseId")
            addedSets(command.count, "count")
        }
        is RemoveSet -> id(command.setId, "setId")
        is SetWeight -> {
            id(command.setId, "setId")
            weight(command.kg)
        }
        is SetReps -> {
            id(command.setId, "setId")
            reps(command.reps)
        }
        is TickSet -> id(command.setId, "setId")
        is LogSet -> {
            id(command.workoutId, "workoutId")
            id(command.setId, "setId")
        }
        is SelectCurrent -> {
            id(command.setId, "setId")
            id(command.workoutExerciseId, "workoutExerciseId")
            if (command.setId == null && command.workoutExerciseId == null) reasons += "a setId or a workoutExerciseId is needed"
        }
        is FinishWorkout -> id(command.workoutId, "workoutId")
        is DeleteWorkout -> id(command.workoutId, "workoutId")
        is ApplyPlan -> {
            if (command.plan.exercises.isEmpty()) reasons += "the plan has no exercises"
            command.plan.exercises.forEachIndexed { i, planned ->
                val at = "exercise ${i + 1}"
                ref(planned.exercise, at)
                if (planned.sets.size !in MIN_PLANNED_SETS..MAX_PLANNED_SETS) {
                    reasons += "$at must have between $MIN_PLANNED_SETS and $MAX_PLANNED_SETS sets"
                }
                planned.sets.forEachIndexed { s, set ->
                    set.weightKg?.let { weight(it, "$at, set ${s + 1}: weight") }
                    set.reps?.let { reps(it, "$at, set ${s + 1}: reps") }
                }
            }
        }
        is SetRest -> {
            id(command.exerciseId, "exerciseId")
            command.seconds?.let { if (it !in 0..MAX_REST_SECONDS) reasons += "rest must be between 0 and $MAX_REST_SECONDS seconds" }
        }
        is CreateExercise -> name(command.name)
        is SaveRoutine -> {
            id(command.workoutId, "workoutId")
            name(command.name)
        }
        is SaveRoutinePlan -> {
            name(command.plan.name)
            if (command.plan.exercises.isEmpty()) reasons += "the routine has no exercises"
            command.plan.exercises.forEachIndexed { i, planned ->
                val at = "exercise ${i + 1}"
                ref(planned.exercise, at)
                if (planned.targetSets !in MIN_TARGET_SETS..MAX_TARGET_SETS) {
                    reasons += "$at must have between $MIN_TARGET_SETS and $MAX_TARGET_SETS target sets"
                }
                planned.targetReps?.let { reps(it, "$at: target reps") }
            }
        }
        is CreateRoutine -> name(command.name)
        is RenameRoutine -> {
            id(command.routineId, "routineId")
            name(command.name)
        }
        is DeleteRoutine -> id(command.routineId, "routineId")
        is DeleteRoutines -> {
            if (command.ids.size !in 1..MAX_DELETE_ROUTINES) reasons += "between 1 and $MAX_DELETE_ROUTINES routine ids are needed"
            if (command.ids.any { it.isBlank() }) reasons += "routine ids must not be blank"
        }
        is MoveRoutineExercise -> id(command.routineExerciseId, "routineExerciseId")
        is AdjustTargetSets -> id(command.routineExerciseId, "routineExerciseId")
        is AddRoutineExercise -> {
            id(command.routineId, "routineId")
            ref(command.exercise)
            if (command.targetSets !in MIN_TARGET_SETS..MAX_TARGET_SETS) {
                reasons += "target sets must be between $MIN_TARGET_SETS and $MAX_TARGET_SETS"
            }
            targetReps(command.targetReps)
        }
        is RemoveRoutineExercise -> id(command.routineExerciseId, "routineExerciseId")
        is SetTargetReps -> {
            id(command.routineExerciseId, "routineExerciseId")
            targetReps(command.reps)
        }
        is CreateProgram -> name(command.name)
        is RenameProgram -> {
            id(command.programId, "programId")
            name(command.name)
        }
        is SetProgramMode -> id(command.programId, "programId")
        is ActivateProgram -> id(command.programId, "programId")
        is DeactivateProgram -> id(command.programId, "programId")
        is DeleteProgram -> id(command.programId, "programId")
        is AddProgramDay -> {
            id(command.programId, "programId")
            id(command.routineId, "routineId")
        }
        is RemoveProgramDay -> id(command.dayId, "dayId")
        is MoveProgramDay -> id(command.dayId, "dayId")
        is ToggleProgramWeekday -> {
            id(command.dayId, "dayId")
            if (command.weekday !in ISO_WEEKDAYS) reasons += "weekday must be between 1 (Monday) and 7 (Sunday)"
        }
    }
    return if (reasons.isEmpty()) Validation.Valid else Validation.Invalid(reasons)
}
