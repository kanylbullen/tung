package io.github.kanylbullen.tung.core.command

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.ProgramMode

/** The target sets of an exercise added to a routine ([Command.AddRoutineExercise]) when the caller names none. */
const val DEFAULT_ROUTINE_TARGET_SETS = 3

/**
 * Everything that changes training data, as plain data. The app's buttons, a
 * future REST API and MCP server, voice and camera all speak this one
 * language, so they cannot drift apart: a new input is a translator from its
 * signal to commands, never a new path into the database.
 *
 * Each command carries only what its caller knows. Ids are the entities'
 * client-generated UUIDs; anything the caller names in words goes through an
 * [ExerciseRef] and is resolved, never guessed ([resolvePlan]). Ranges are
 * checked by [validate] before a [CommandHandler] runs anything, and every
 * command has a stable wire name in [CommandNames].
 *
 * The data classes are nested so that [CommandNames]' completeness test can
 * list them without kotlin-reflect.
 */
sealed interface Command {

    // --- workouts ---

    /**
     * Starts a workout, or returns the one in progress (there is at most one).
     * With [routineId] it is [StartRoutine]; [programDayId] is recorded on the
     * workout and needs a [routineId].
     */
    data class StartWorkout(val routineId: String? = null, val programDayId: String? = null) : Command

    /** Starts a workout from a routine, or returns the one in progress; [programDayId] when started from a program day. */
    data class StartRoutine(val routineId: String, val programDayId: String? = null) : Command

    /**
     * Adds an exercise at the end of the workout with [sets] sets (1..20), or,
     * when null, as many as the exercise had last session (one with no
     * history). Each set is pre-filled at its own position
     * ([io.github.kanylbullen.tung.core.addedExerciseSets]): last session's
     * set 1, set 2, …, past its end a copy of the set before, 0 × 0 with no
     * history.
     */
    data class AddExercise(val workoutId: String, val exercise: ExerciseRef, val sets: Int? = null) : Command

    data class RemoveExercise(val workoutExerciseId: String) : Command

    /**
     * Adds [count] sets (1..20, null for one) to an exercise, each pre-filled
     * by the usual rule: a copy of the set before it, else last session's set
     * at that position.
     */
    data class AddSet(val workoutExerciseId: String, val count: Int? = null) : Command

    data class RemoveSet(val setId: String) : Command

    /** Writes a weight; with [applyToOpenSets] also to every un-ticked set of the same exercise. */
    data class SetWeight(val setId: String, val kg: Double, val applyToOpenSets: Boolean = false) : Command

    /** Writes reps; with [applyToOpenSets] also to every un-ticked set of the same exercise. */
    data class SetReps(val setId: String, val reps: Int, val applyToOpenSets: Boolean = false) : Command

    /** The tick on a row: ticks or un-ticks one set, nothing else. */
    data class TickSet(val setId: String, val done: Boolean) : Command

    /**
     * "Log set": ticks the current set and, if it was the last of its
     * exercise, adds the next one (not past a routine's target sets). The app
     * passes the [setId] it shows as current (selection and the touched
     * exercise live in its ViewModel); without one the handler takes the first
     * un-ticked set of the first exercise that has one ([io.github.kanylbullen.tung.core.currentSet]).
     */
    data class LogSet(val workoutId: String, val setId: String? = null) : Command

    /**
     * Makes a set the current one: [setId] itself, or the first un-ticked set
     * of [workoutExerciseId]. Changes no data; the handler checks and resolves
     * it and returns the set, and the caller keeps the selection.
     */
    data class SelectCurrent(val setId: String? = null, val workoutExerciseId: String? = null) : Command

    /** Drops un-ticked and empty sets, then empty exercises; a workout with nothing logged is deleted. */
    data class FinishWorkout(val workoutId: String) : Command

    data class DeleteWorkout(val workoutId: String) : Command

    /**
     * Applies a whole workout at once ("deadlift, bench press and biceps curl,
     * five sets of ten, 50, 60 and 20 kg"): starts or reuses the workout in
     * progress and adds the planned exercises and sets, nothing ticked, in one
     * transaction. A plan that names something unknown creates nothing and
     * returns the questions.
     */
    data class ApplyPlan(val plan: WorkoutPlan) : Command

    // --- exercises ---

    /** The rest timer of an exercise in seconds; 0 turns it off, null follows the default. */
    data class SetRest(val exerciseId: String, val seconds: Int?) : Command

    /** A custom exercise. */
    data class CreateExercise(val name: String, val muscleGroup: MuscleGroup, val equipment: Equipment) : Command

    // --- routines ---

    /** Saves a finished workout's logged sets as a new routine. */
    data class SaveRoutine(val workoutId: String, val name: String) : Command

    /** Creates a routine from a plan (a dictated programme), not from a workout. */
    data class SaveRoutinePlan(val plan: RoutinePlan) : Command

    /** Creates an empty routine called [name], to be filled with [AddRoutineExercise]. */
    data class CreateRoutine(val name: String) : Command

    data class RenameRoutine(val routineId: String, val name: String) : Command

    /** Deletes a routine; workouts started from it are kept. */
    data class DeleteRoutine(val routineId: String) : Command

    /** Moves one routine exercise [delta] places (-1 up, +1 down). */
    data class MoveRoutineExercise(val routineExerciseId: String, val delta: Int) : Command

    /** Adds [delta] to a routine exercise's target sets, kept within the allowed range. */
    data class AdjustTargetSets(val routineExerciseId: String, val delta: Int) : Command

    /**
     * Appends an exercise to a saved routine, after its last one, with
     * [targetSets] (1..20) and, if given, [targetReps] (1..999). The exercise is
     * resolved like [AddExercise]'s; an unknown name asks instead of guessing.
     */
    data class AddRoutineExercise(
        val routineId: String,
        val exercise: ExerciseRef,
        val targetSets: Int = DEFAULT_ROUTINE_TARGET_SETS,
        val targetReps: Int? = null,
    ) : Command

    /** Removes one exercise from a routine; the ones after it move up. Workouts started from it are kept. */
    data class RemoveRoutineExercise(val routineExerciseId: String) : Command

    /** The reps a routine exercise aims at per set, 1..999; null for none. */
    data class SetTargetReps(val routineExerciseId: String, val reps: Int?) : Command

    // --- programs ---

    data class CreateProgram(val name: String, val mode: ProgramMode) : Command

    data class RenameProgram(val programId: String, val name: String) : Command

    /** Switches a program between fixed (weekdays) and rotating (in order); its days and their weekdays are kept. */
    data class SetProgramMode(val programId: String, val mode: ProgramMode) : Command

    /** Makes this the one active program. */
    data class ActivateProgram(val programId: String) : Command

    data class DeactivateProgram(val programId: String) : Command

    /** Deletes a program and its days; workouts started from it are kept. */
    data class DeleteProgram(val programId: String) : Command

    /** Adds a routine as the program's last day. */
    data class AddProgramDay(val programId: String, val routineId: String) : Command

    data class RemoveProgramDay(val dayId: String) : Command

    /** Moves one program day [delta] places (-1 up, +1 down). */
    data class MoveProgramDay(val dayId: String, val delta: Int) : Command

    /** Switches an ISO weekday (1 Monday … 7 Sunday) on or off for one day. */
    data class ToggleProgramWeekday(val dayId: String, val weekday: Int) : Command
}

/**
 * How a caller names an exercise. The app's picker knows ids; a plan from a
 * voice or a model knows words ([ByName]); an API client may know catalogue
 * keys. Resolution ([resolvePlan], [ExerciseResolver]) turns each into an id or
 * a question.
 */
sealed interface ExerciseRef {
    /** A core catalogue key: `bench_press`. */
    data class ByKey(val nameKey: String) : ExerciseRef

    /** An exercise's id in the database. */
    data class ById(val id: String) : ExerciseRef

    /**
     * Words: "Bänkpress", "bench press", "deadlift". Matched against every
     * name an exercise answers to (Swedish and English catalogue names,
     * custom names, later aliases) with [normalizeExerciseName]; only an exact
     * match resolves.
     */
    data class ByName(val text: String) : ExerciseRef

    /** An entry of the extended catalogue by its free-exercise-db id; its row is created the first time. */
    data class Extended(val fedId: String) : ExerciseRef
}

/** One planned set; a missing number is filled by the pre-fill rule ([plannedSetValues]). */
data class PlannedSet(val weightKg: Double?, val reps: Int?)

data class PlannedExercise(val exercise: ExerciseRef, val sets: List<PlannedSet>) {
    companion object {
        /** [sets] identical sets: "five sets of ten at 60 kg". */
        fun uniform(exercise: ExerciseRef, sets: Int, reps: Int?, weightKg: Double?): PlannedExercise =
            PlannedExercise(exercise, List(sets.coerceAtLeast(0)) { PlannedSet(weightKg = weightKg, reps = reps) })
    }
}

/** A whole workout, in order, for [Command.ApplyPlan]. */
data class WorkoutPlan(val exercises: List<PlannedExercise>)

/** One exercise of a [RoutinePlan]: how many sets it asks for and, if any, the reps it aims at. */
data class RoutinePlanExercise(val exercise: ExerciseRef, val targetSets: Int, val targetReps: Int? = null)

/** A routine to create from words rather than from a workout, for [Command.SaveRoutinePlan]. */
data class RoutinePlan(val name: String, val exercises: List<RoutinePlanExercise>)
