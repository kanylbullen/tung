package io.github.kanylbullen.tung.server.mcp

/**
 * What each MCP tool and prompt says to an assistant. Written for a model
 * that knows nothing about Tung: what the tool does, which ids it takes and
 * where they come from, the units, and that unknown exercise names come back
 * as questions. `McpTest` checks that every command has a description.
 */
object McpDescriptions {

    private const val REF =
        "An exercise is given as {\"key\": \"bench_press\"} (a core catalogue key), {\"id\": \"…\"}, {\"name\": \"Bench press\"} " +
            "(any language the catalogue has, e.g. \"Bänkpress\") or {\"extended\": \"…\"} (an extended-catalogue id from the exercises tool). " +
            "Only an exact name resolves; anything else comes back with status needs_answers and suggestions, and nothing is written."

    private const val IDS = "Ids come from current_workout, routines and programs."

    private val commands: Map<String, String> = mapOf(
        "start_workout" to
            "Starts an empty workout and returns its id (ids.workoutId), or returns the workout already in progress: there is at most one. " +
            "With routine_id it starts from that routine, like start_routine. Then add exercises with add_exercise, or a whole plan with apply_plan.",
        "start_routine" to
            "Starts a workout from a saved routine: its exercises in order, each with the routine's target number of sets, pre-filled with " +
            "last session's weights (kg) and reps. Returns ids.workoutId. If a workout is already in progress, that one is returned and " +
            "nothing is created. program_day_id records which program day this is (from the programs tool).",
        "add_exercise" to
            "Adds an exercise at the end of a workout, with one set pre-filled from last time. Returns ids.workoutExerciseId and " +
            "ids.exerciseId. $REF",
        "remove_exercise" to "Removes an exercise and its sets from a workout. workout_exercise_id comes from current_workout.",
        "add_set" to
            "Adds a set to an exercise in a workout, pre-filled with the exercise's last set (else last session's set at that position). " +
            "Returns ids.setId. workout_exercise_id comes from current_workout.",
        "remove_set" to "Removes one set from a workout. set_id comes from current_workout.",
        "set_weight" to
            "Sets a set's weight in kilograms (0 to 999; 2.5 kg steps are common). With apply_to_open_sets, every un-ticked set of the " +
            "same exercise gets it too. set_id comes from current_workout.",
        "set_reps" to
            "Sets a set's repetitions (0 to 999). With apply_to_open_sets, every un-ticked set of the same exercise gets them too. " +
            "set_id comes from current_workout.",
        "tick_set" to
            "Ticks (done: true) or un-ticks one set and nothing else. When the user has just done a set, use log_set instead, which " +
            "also prepares the next set.",
        "log_set" to
            "Logs a set the user has just done: ticks it and, if it was the exercise's last set, adds the next one pre-filled with the " +
            "same numbers (never past a routine's target sets). Without set_id it logs the current set (current_set_id in " +
            "current_workout). Set the real weight and reps first with set_weight and set_reps if they differ; a set with 0 reps is " +
            "refused. Returns ids.setId and, when a set was added, ids.addedSetId.",
        "select_current" to
            "Checks which set would be the current one: set_id itself, or the first un-ticked set of workout_exercise_id. Changes " +
            "nothing; returns ids.setId and ids.workoutExerciseId, which you can pass to log_set.",
        "finish_workout" to
            "Finishes a workout. Sets that are not ticked with reps above 0 are dropped, then exercises left without sets; a workout " +
            "with nothing logged is deleted instead of saved (ids is then empty). Confirm with the user before finishing.",
        "delete_workout" to "Deletes a workout with all its sets, in progress or finished. This cannot be undone: confirm with the user first.",
        "apply_plan" to
            "Applies a whole workout at once, e.g. \"deadlift, bench press and biceps curl, five sets of ten at 50, 60 and 20 kg\": " +
            "starts a workout (or uses the one in progress) and appends the exercises in order with their planned sets, none ticked. " +
            "Plan: {\"exercises\": [{\"exercise\": {\"key\": \"deadlift\"}, \"set_count\": 5, \"reps\": 10, \"weight_kg\": 50}, " +
            "{\"exercise\": {\"name\": \"Bench press\"}, \"sets\": [{\"weight_kg\": 60, \"reps\": 10}, {\"weight_kg\": 62.5, \"reps\": 8}]}]}. " +
            "Each exercise takes either sets (one object per set) or set_count with reps and weight_kg; a number left out is " +
            "pre-filled from last session. 1 to 50 sets per exercise, weight_kg 0 to 999 (kilograms), reps 0 to 999. Every exercise " +
            "is resolved before anything is written: if a name is unknown or ambiguous the result has status needs_answers with the " +
            "questions and suggestions; ask the user, then send the whole plan again with the chosen refs. Returns ids.workoutId. $REF",
        "set_rest" to
            "Sets an exercise's rest timer between sets, in seconds (0 to 3600); 0 turns it off, null follows the app's default. " +
            "exercise_id is the exercise's own id (from exercises), not a workout_exercise_id.",
        "create_exercise" to
            "Creates a custom exercise. Look in the exercises tool first: the core catalogue has about 70 exercises with names in " +
            "several languages and the extended catalogue about 600 more. Returns ids.exerciseId.",
        "save_routine" to
            "Saves a workout's logged sets as a new routine (a workout template): its exercises in order, with as many target sets " +
            "as were logged and the most common rep count as target reps. Returns ids.routineId. Ask the user for the name.",
        "save_routine_plan" to
            "Saves a new routine (a workout template) from a plan, such as a programme designed with the user. Plan: " +
            "{\"name\": \"Push A\", \"exercises\": [{\"exercise\": {\"key\": \"bench_press\"}, \"target_sets\": 4, \"target_reps\": 8}, " +
            "{\"exercise\": {\"key\": \"overhead_press\"}, \"target_sets\": 3, \"target_reps\": 10}]}. target_sets 1 to 20, target_reps " +
            "optional. Unknown names come back as questions (status needs_answers) and nothing is saved. Only call this after the " +
            "user has confirmed the routine. Returns ids.routineId: start it with start_routine, or add it to a program with " +
            "add_program_day. $REF",
        "rename_routine" to "Renames a routine. $IDS",
        "delete_routine" to
            "Deletes a routine and the program days that use it. Workouts done from it stay in the history. Confirm with the user first.",
        "move_routine_exercise" to "Moves an exercise within its routine: delta -1 moves it up one place, 1 down. routine_exercise_id comes from routines.",
        "adjust_target_sets" to
            "Changes how many sets a routine asks for of one exercise by delta (1 adds a set, -1 removes one; kept within 1 to 20). " +
            "routine_exercise_id comes from routines.",
        "add_routine_exercise" to
            "Adds an exercise to a saved routine, after its last one, with target_sets (1 to 20, default 3) and optionally " +
            "target_reps (1 to 999). Returns ids.routineExerciseId and ids.exerciseId. routine_id comes from routines. $REF",
        "remove_routine_exercise" to
            "Removes one exercise from a routine; the ones after it move up. Workouts already done from the routine are kept. " +
            "routine_exercise_id comes from routines.",
        "set_target_reps" to
            "Sets the reps a routine asks for per set of one exercise (1 to 999), or clears it with target_reps null. " +
            "routine_exercise_id comes from routines.",
        "create_program" to
            "Creates an empty, inactive program: an ordered list of routines. Mode fixed puts each day on chosen weekdays " +
            "(toggle_program_weekday); rotating runs day 1, 2, 3 in order whatever the date. Add days with add_program_day and make it " +
            "the active one with activate_program. Returns ids.programId.",
        "rename_program" to "Renames a program. $IDS",
        "set_program_mode" to
            "Switches a program between fixed (each day on chosen weekdays, toggle_program_weekday) and rotating (day 1, 2, 3 in " +
            "order whatever the date). Its days and their weekdays are kept. program_id comes from programs.",
        "activate_program" to "Makes this the user's one active program; any other active program is switched off.",
        "deactivate_program" to "Switches a program off, leaving no active program if it was the active one.",
        "delete_program" to "Deletes a program and its days. Routines and workouts stay. Confirm with the user first.",
        "add_program_day" to "Adds a routine as the program's last day, with no weekdays yet. Returns ids.programDayId.",
        "remove_program_day" to "Removes one day from its program; the days after it move up.",
        "move_program_day" to "Moves a day within its program: delta -1 up one place, 1 down. day_id comes from programs.",
        "toggle_program_weekday" to
            "Switches one ISO weekday (1 Monday … 7 Sunday) on or off for a program day; a fixed-mode program uses these. " +
            "day_id comes from programs.",
    )

    fun command(name: String): String = commands[name] ?: error("No MCP description for the command $name")

    /** For the completeness test. */
    val describedCommands: Set<String> get() = commands.keys

    const val HISTORY =
        "The user's finished workouts, newest first: when (started_at and ended_at, epoch milliseconds), each exercise by name " +
            "and catalogue key with its logged sets (weight_kg in kilograms, reps). limit 1 to 50, default 10; pass next_before as " +
            "before to read further back. Use it to see what the user trains and lifts before suggesting anything."

    const val LAST_TIME =
        "The sets the user did of one exercise in their latest finished workout that had it (\"last time: 80 kg × 8\"). " +
            "exercise: {\"key\": …}, {\"id\": …}, {\"name\": …} or {\"extended\": …}, or just a name; an unknown or ambiguous name " +
            "comes back with status needs_answers and suggestions."

    const val CURRENT_WORKOUT =
        "The workout in progress, or workout: null. Every exercise with its workout_exercise_id, name and sets (set_id, weight_kg " +
            "in kilograms, reps, done), plus current_set_id: the set log_set logs when given no set_id. Read it before changing sets."

    const val ROUTINES =
        "The user's routines (workout templates): id, name, when last used, and the exercises in order with routine_exercise_id, " +
            "target_sets and target_reps."

    const val PROGRAMS =
        "The user's programs: id, name, mode (fixed: each day on its weekdays, 1 Monday … 7 Sunday; rotating: the days in order), " +
            "whether it is the active one, last_completed_day_id (a rotating program continues with the day after it), and its days " +
            "with day_id, routine_id and routine_name."

    const val EXERCISES =
        "Finds exercises. Lists the core catalogue and the user's own exercises (id, catalogue key, every name it has in each " +
            "language, muscle group, equipment, rest timer); with a query of two or more characters also matches from the extended " +
            "catalogue (about 600, English names; use them as {\"extended\": id}). Without a query it lists everything the user has."

    private const val COMMON_RULES =
        "Rules: read my data with the tools first (history, last_time, routines, programs, exercises); never guess numbers you " +
            "can look up. Weights are in kilograms. Answer in my language, the one I write to you in. Refer to exercises by their " +
            "catalogue key in tool calls and by their name in my language when you talk to me. Do not change anything without asking: " +
            "save a routine with save_routine_plan only after I have confirmed it."

    fun suggestProgram(goal: String?, daysPerWeek: String?, equipment: String?): String = """
        Help me plan my training in Tung.

        Goal: ${goal?.takeIf { it.isNotBlank() } ?: "not given; ask me"}
        Days per week: ${daysPerWeek?.takeIf { it.isNotBlank() } ?: "not given; ask me"}
        Equipment: ${equipment?.takeIf { it.isNotBlank() } ?: "not given; ask me"}

        1. Read what I already do: history (several pages, about the last three months), routines and programs. Look up exercises
           with the exercises tool so every one you suggest exists in the catalogue.
        2. Propose a program: one routine per training day, each a list of exercises with target sets and reps, and how to
           progress week to week. Build on my history: keep the lifts I do, and base starting weights on what I lift now.
        3. Wait for my answer and adjust.
        4. When I confirm, save each routine with save_routine_plan, then create_program (fixed with weekdays via
           toggle_program_weekday, or rotating), add_program_day for each routine in order, and activate_program.

        $COMMON_RULES
    """.trimIndent()

    val NEXT_WORKOUT: String = """
        What should I train today?

        1. Read my programs. If one is active, work out today's day: in a fixed program the day whose weekdays include today;
           in a rotating one the day after last_completed_day_id (the first if there is none). Without an active program, look
           at my history and routines and suggest what is due.
        2. For each exercise of that day, read last_time and suggest weight and reps: a small step up where I hit my target reps
           last time, the same where I did not.
        3. Show me the plan. If I want to start it, call start_routine with the routine and program day, then set_weight and
           set_reps for the sets you suggested, once I agree.

        $COMMON_RULES
    """.trimIndent()

    val PLATEAUS: String = """
        Find where I have stopped progressing.

        1. Read my history, paging back with next_before over about the last three months.
        2. For each exercise I train regularly, follow the best set per session (heaviest weight for the reps, or an estimated
           one-rep max) over time. Name the exercises with no progress for four weeks or more, with the numbers that show it.
        3. For each, suggest one concrete change (a deload, another rep range, a variation, more or less volume) and why.
        4. If a change means a new routine, describe it, and save it with save_routine_plan only after I confirm.

        $COMMON_RULES
    """.trimIndent()
}
