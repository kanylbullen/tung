package io.github.kanylbullen.tung.core.command

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

/**
 * The stable snake_case name of every [Command]: the REST route, the MCP tool
 * name and what a log or a voice transcript records. **A name never changes
 * once it exists**, like a catalogue key: clients and saved prompts outside
 * this repository call it. Renaming a Kotlin class is fine; renaming its wire
 * name is a breaking change, so add a new command instead.
 *
 * `CommandNamesTest` fails when a [Command] has no name here.
 */
object CommandNames {
    val all: Map<Class<out Command>, String> = linkedMapOf(
        StartWorkout::class.java to "start_workout",
        StartRoutine::class.java to "start_routine",
        AddExercise::class.java to "add_exercise",
        RemoveExercise::class.java to "remove_exercise",
        AddSet::class.java to "add_set",
        RemoveSet::class.java to "remove_set",
        SetWeight::class.java to "set_weight",
        SetReps::class.java to "set_reps",
        TickSet::class.java to "tick_set",
        LogSet::class.java to "log_set",
        SelectCurrent::class.java to "select_current",
        FinishWorkout::class.java to "finish_workout",
        DeleteWorkout::class.java to "delete_workout",
        ApplyPlan::class.java to "apply_plan",
        SetRest::class.java to "set_rest",
        CreateExercise::class.java to "create_exercise",
        SaveRoutine::class.java to "save_routine",
        SaveRoutinePlan::class.java to "save_routine_plan",
        CreateRoutine::class.java to "create_routine",
        RenameRoutine::class.java to "rename_routine",
        DeleteRoutine::class.java to "delete_routine",
        DeleteRoutines::class.java to "delete_routines",
        MoveRoutineExercise::class.java to "move_routine_exercise",
        AdjustTargetSets::class.java to "adjust_target_sets",
        AddRoutineExercise::class.java to "add_routine_exercise",
        RemoveRoutineExercise::class.java to "remove_routine_exercise",
        SetTargetReps::class.java to "set_target_reps",
        CreateProgram::class.java to "create_program",
        RenameProgram::class.java to "rename_program",
        SetProgramMode::class.java to "set_program_mode",
        ActivateProgram::class.java to "activate_program",
        DeactivateProgram::class.java to "deactivate_program",
        DeleteProgram::class.java to "delete_program",
        AddProgramDay::class.java to "add_program_day",
        RemoveProgramDay::class.java to "remove_program_day",
        MoveProgramDay::class.java to "move_program_day",
        ToggleProgramWeekday::class.java to "toggle_program_weekday",
    )

    /** The wire name of [command]. */
    fun of(command: Command): String = all.getValue(command.javaClass)

    /** The command class a wire name stands for, or null. */
    fun classOf(name: String): Class<out Command>? = all.entries.firstOrNull { it.value == name }?.key
}
