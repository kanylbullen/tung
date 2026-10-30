package io.github.kanylbullen.tung.core.command

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.ProgramMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandWireTest {

    private val id = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private val ex = """{"key": "bench_press"}"""

    /** One valid body per wire name. */
    private val samples: Map<String, String> = mapOf(
        "start_workout" to "{}",
        "start_routine" to """{"routine_id": "$id"}""",
        "add_exercise" to """{"workout_id": "$id", "exercise": $ex}""",
        "remove_exercise" to """{"workout_exercise_id": "$id"}""",
        "add_set" to """{"workout_exercise_id": "$id"}""",
        "remove_set" to """{"set_id": "$id"}""",
        "set_weight" to """{"set_id": "$id", "weight_kg": 82.5}""",
        "set_reps" to """{"set_id": "$id", "reps": 8, "apply_to_open_sets": true}""",
        "tick_set" to """{"set_id": "$id", "done": true}""",
        "log_set" to """{"workout_id": "$id"}""",
        "select_current" to """{"workout_exercise_id": "$id"}""",
        "finish_workout" to """{"workout_id": "$id"}""",
        "delete_workout" to """{"workout_id": "$id"}""",
        "apply_plan" to """{"exercises": [{"exercise": $ex, "set_count": 5, "reps": 10, "weight_kg": 60}]}""",
        "set_rest" to """{"exercise_id": "$id", "seconds": 120}""",
        "create_exercise" to """{"name": "Zercher squat", "muscle_group": "legs", "equipment": "barbell"}""",
        "save_routine" to """{"workout_id": "$id", "name": "Push"}""",
        "save_routine_plan" to """{"name": "Push", "exercises": [{"exercise": $ex, "target_sets": 3, "target_reps": 8}]}""",
        "create_routine" to """{"name": "Legs"}""",
        "rename_routine" to """{"routine_id": "$id", "name": "Pull"}""",
        "delete_routine" to """{"routine_id": "$id"}""",
        "delete_routines" to """{"routine_ids": ["$id"]}""",
        "move_routine_exercise" to """{"routine_exercise_id": "$id", "delta": -1}""",
        "adjust_target_sets" to """{"routine_exercise_id": "$id", "delta": 1}""",
        "add_routine_exercise" to """{"routine_id": "$id", "exercise": $ex, "target_sets": 4, "target_reps": 8}""",
        "remove_routine_exercise" to """{"routine_exercise_id": "$id"}""",
        "set_target_reps" to """{"routine_exercise_id": "$id", "target_reps": 12}""",
        "create_program" to """{"name": "PPL", "mode": "rotating"}""",
        "rename_program" to """{"program_id": "$id", "name": "PPL 2"}""",
        "set_program_mode" to """{"program_id": "$id", "mode": "fixed"}""",
        "activate_program" to """{"program_id": "$id"}""",
        "deactivate_program" to """{"program_id": "$id"}""",
        "delete_program" to """{"program_id": "$id"}""",
        "add_program_day" to """{"program_id": "$id", "routine_id": "$id"}""",
        "remove_program_day" to """{"day_id": "$id"}""",
        "move_program_day" to """{"day_id": "$id", "delta": 1}""",
        "toggle_program_weekday" to """{"day_id": "$id", "weekday": 3}""",
    )

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun decoded(name: String, body: String): Command {
        val result = CommandWire.decode(name, obj(body))
        assertTrue("$name: $result", result is WireDecoding.Decoded)
        return (result as WireDecoding.Decoded).command
    }

    @Test
    fun everyCommandHasASchemaAndDecodesToItsOwnClass() {
        assertEquals(CommandNames.all.values.toSet(), samples.keys)
        for (name in CommandWire.names) {
            val schema = assertNotNull(name, CommandWire.schema(name)).let { CommandWire.schema(name)!! }
            assertEquals("object", schema["type"]!!.jsonPrimitive.content)
            val properties = schema["properties"]!!.jsonObject
            schema["required"]!!.jsonArray.forEach { assertTrue("$name requires a declared field", it.jsonPrimitive.content in properties) }
            properties.values.forEach { assertNotNull("$name: every field is described", it.jsonObject["description"]) }
            val command = decoded(name, samples.getValue(name))
            assertEquals(name, CommandNames.of(command))
            assertEquals("$name: the sample is valid", Validation.Valid, validate(command))
        }
    }

    @Test
    fun fieldsMapOntoTheCommand() {
        assertEquals(Command.SetWeight(id, 82.5, false), decoded("set_weight", samples.getValue("set_weight")))
        assertEquals(Command.SetReps(id, 8, true), decoded("set_reps", samples.getValue("set_reps")))
        assertEquals(
            Command.CreateExercise("Zercher squat", MuscleGroup.LEGS, Equipment.BARBELL),
            decoded("create_exercise", samples.getValue("create_exercise")),
        )
        assertEquals(Command.CreateProgram("PPL", ProgramMode.ROTATING), decoded("create_program", samples.getValue("create_program")))
        assertEquals(Command.SetRest(id, null), decoded("set_rest", """{"exercise_id": "$id", "seconds": null}"""))
        assertEquals(Command.SetRest(id, null), decoded("set_rest", """{"exercise_id": "$id"}"""))
    }

    @Test
    fun logSetTakesAnOptionalAddNextThatDefaultsToFalse() {
        assertEquals(Command.LogSet(id, null, addNext = false), decoded("log_set", samples.getValue("log_set")))
        assertEquals(Command.LogSet(id, id, addNext = true), decoded("log_set", """{"workout_id": "$id", "set_id": "$id", "add_next": true}"""))
        assertEquals(Command.LogSet(id, null, addNext = false), decoded("log_set", """{"workout_id": "$id", "add_next": false}"""))
        val reasons = (CommandWire.decode("log_set", obj("""{"workout_id": "$id", "add_next": "maybe"}""")) as WireDecoding.Invalid).reasons
        assertTrue(reasons.toString(), reasons.contains("add_next must be true or false"))
        val schema = CommandWire.schema("log_set")!!
        assertEquals("boolean", schema["properties"]!!.jsonObject["add_next"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue("add_next is optional", schema["required"]!!.jsonArray.none { it.jsonPrimitive.content == "add_next" })
    }

    @Test
    fun addExerciseTakesASetCountAndAddSetACount() {
        val bench = ExerciseRef.ByKey("bench_press")
        assertEquals(Command.AddExercise(id, bench, null), decoded("add_exercise", samples.getValue("add_exercise")))
        assertEquals(Command.AddExercise(id, bench, 4), decoded("add_exercise", """{"workout_id": "$id", "exercise": $ex, "sets": 4}"""))
        assertEquals(Command.AddExercise(id, bench, null), decoded("add_exercise", """{"workout_id": "$id", "exercise": $ex, "sets": null}"""))
        assertEquals(Command.AddSet(id, null), decoded("add_set", samples.getValue("add_set")))
        assertEquals(Command.AddSet(id, 3), decoded("add_set", """{"workout_exercise_id": "$id", "count": "3"}"""))
        fun reasons(name: String, body: String) = (CommandWire.decode(name, obj(body)) as WireDecoding.Invalid).reasons
        assertTrue(reasons("add_exercise", """{"workout_id": "$id", "exercise": $ex, "sets": 2.5}""").contains("sets must be a whole number"))
        assertTrue(reasons("add_set", """{"workout_exercise_id": "$id", "count": "many"}""").contains("count must be a whole number"))
        listOf("add_exercise" to "sets", "add_set" to "count").forEach { (name, field) ->
            val schema = CommandWire.schema(name)!!
            assertTrue("$name declares $field", field in schema["properties"]!!.jsonObject)
            assertTrue("$field is optional", schema["required"]!!.jsonArray.none { it.jsonPrimitive.content == field })
        }
    }

    @Test
    fun routineEditingFieldsMapOntoTheCommand() {
        val bench = ExerciseRef.ByKey("bench_press")
        assertEquals(Command.AddRoutineExercise(id, bench, 4, 8), decoded("add_routine_exercise", samples.getValue("add_routine_exercise")))
        assertEquals(
            "target sets default to 3, target reps to none",
            Command.AddRoutineExercise(id, ExerciseRef.ByName("Bänkpress"), DEFAULT_ROUTINE_TARGET_SETS, null),
            decoded("add_routine_exercise", """{"routine_id": "$id", "exercise": "Bänkpress"}"""),
        )
        assertEquals(
            Command.AddRoutineExercise(id, ExerciseRef.Extended("Zercher_Squats"), 3, null),
            decoded("add_routine_exercise", """{"routine_id": "$id", "exercise": {"extended": "Zercher_Squats"}, "target_sets": null, "target_reps": null}"""),
        )
        assertEquals(Command.RemoveRoutineExercise(id), decoded("remove_routine_exercise", samples.getValue("remove_routine_exercise")))
        assertEquals(Command.SetTargetReps(id, 12), decoded("set_target_reps", samples.getValue("set_target_reps")))
        assertEquals(Command.SetTargetReps(id, null), decoded("set_target_reps", """{"routine_exercise_id": "$id", "target_reps": null}"""))
        assertEquals(Command.SetProgramMode(id, ProgramMode.FIXED), decoded("set_program_mode", samples.getValue("set_program_mode")))
        assertEquals(Command.SetProgramMode(id, ProgramMode.ROTATING), decoded("set_program_mode", """{"program_id": "$id", "mode": "rotating"}"""))
    }

    @Test
    fun routineEditingShapesAreChecked() {
        fun reasons(name: String, body: String) = (CommandWire.decode(name, obj(body)) as WireDecoding.Invalid).reasons
        assertTrue(reasons("add_routine_exercise", """{"routine_id": "$id"}""").contains("exercise is required"))
        assertTrue(reasons("add_routine_exercise", """{"routine_id": "$id", "exercise": $ex, "target_sets": 2.5}""").contains("target_sets must be a whole number"))
        assertTrue(reasons("remove_routine_exercise", "{}").contains("routine_exercise_id is required"))
        // Clearing the target is explicit: an absent field is not read as null.
        assertTrue(reasons("set_target_reps", """{"routine_exercise_id": "$id"}""").contains("target_reps is required"))
        assertTrue(reasons("set_target_reps", """{"routine_exercise_id": "$id", "target_reps": "many"}""").contains("target_reps must be a whole number or null"))
        assertTrue(reasons("set_program_mode", """{"program_id": "$id", "mode": "weekly"}""").contains("mode must be one of fixed, rotating"))
        assertTrue(reasons("set_program_mode", """{"program_id": "$id"}""").contains("mode is required"))
    }

    @Test
    fun anEmptyRoutineTakesOnlyAName() {
        assertEquals(Command.CreateRoutine("Legs"), decoded("create_routine", samples.getValue("create_routine")))
        val schema = CommandWire.schema("create_routine")!!
        assertEquals(setOf("name"), schema["properties"]!!.jsonObject.keys)
        assertEquals(listOf("name"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        fun reasons(body: String) = (CommandWire.decode("create_routine", obj(body)) as WireDecoding.Invalid).reasons
        assertTrue(reasons("{}").contains("name is required"))
        assertTrue(reasons("""{"name": 3}""").contains("name must be a string"))
        assertTrue(reasons("""{"name": "Legs", "exercises": []}""").any { "unknown field 'exercises'" in it })
    }

    @Test
    fun plansInBothShapes() {
        val uniform = decoded("apply_plan", samples.getValue("apply_plan")) as Command.ApplyPlan
        assertEquals(
            WorkoutPlan(listOf(PlannedExercise.uniform(ExerciseRef.ByKey("bench_press"), 5, reps = 10, weightKg = 60.0))),
            uniform.plan,
        )
        val explicit = decoded(
            "apply_plan",
            """{"exercises": [{"exercise": "Bänkpress", "sets": [{"weight_kg": 60, "reps": 10}, {"reps": 8}, {}]},
                              {"exercise": {"extended": "Zercher_Squats"}, "set_count": 2}]}""",
        ) as Command.ApplyPlan
        assertEquals(
            WorkoutPlan(
                listOf(
                    PlannedExercise(
                        ExerciseRef.ByName("Bänkpress"),
                        listOf(PlannedSet(60.0, 10), PlannedSet(null, 8), PlannedSet(null, null)),
                    ),
                    PlannedExercise.uniform(ExerciseRef.Extended("Zercher_Squats"), 2, reps = null, weightKg = null),
                ),
            ),
            explicit.plan,
        )
        val routine = decoded("save_routine_plan", samples.getValue("save_routine_plan")) as Command.SaveRoutinePlan
        assertEquals(RoutinePlan("Push", listOf(RoutinePlanExercise(ExerciseRef.ByKey("bench_press"), 3, 8))), routine.plan)
    }

    @Test
    fun assistantsMaySendNumbersAsStrings() {
        assertEquals(Command.SetReps(id, 8, false), decoded("set_reps", """{"set_id": "$id", "reps": "8"}"""))
        assertEquals(Command.SetWeight(id, 80.0, true), decoded("set_weight", """{"set_id": "$id", "weight_kg": "80", "apply_to_open_sets": "true"}"""))
    }

    @Test
    fun everyProblemIsNamed() {
        val result = CommandWire.decode("set_reps", obj("""{"reps": 8.5, "weight": 3, "apply_to_open_sets": "maybe"}"""))
        val reasons = (result as WireDecoding.Invalid).reasons
        assertTrue(reasons.toString(), reasons.any { "unknown field 'weight'" in it })
        assertTrue(reasons.toString(), "set_id is required" in reasons)
        assertTrue(reasons.toString(), "reps must be a whole number" in reasons)
        assertTrue(reasons.toString(), "apply_to_open_sets must be true or false" in reasons)

        val plan = CommandWire.decode("apply_plan", obj("""{"exercises": [{"exercise": {"key": "a", "name": "b"}, "sets": [{"kg": 1}], "set_count": 2}]}"""))
        val planReasons = (plan as WireDecoding.Invalid).reasons
        assertTrue(planReasons.toString(), planReasons.any { it.startsWith("exercises[0].exercise must be an object with exactly one") })
        assertTrue(planReasons.toString(), planReasons.any { "either sets or set_count" in it })

        assertTrue(CommandWire.decode("lift_heavy", obj("{}")) is WireDecoding.Invalid)
        assertTrue(CommandWire.decode("create_program", obj("""{"name": "x", "mode": "random"}""")) is WireDecoding.Invalid)
    }

    @Test
    fun resultsAsJson() {
        assertEquals(
            obj("""{"status": "done", "ids": {"workoutId": "$id"}}"""),
            CommandWire.encode(CommandResult.Done(mapOf(CommandIds.WORKOUT_ID to id))),
        )
        assertEquals(obj("""{"status": "rejected", "reasons": ["no workout"]}"""), CommandWire.encode(CommandResult.Rejected(listOf("no workout"))))
        val question = CommandWire.encode(
            CommandResult.NeedsAnswers(
                listOf(UnresolvedExercise("bänk", listOf(ExerciseCandidate(id, listOf("Bench press", "Bänkpress"), nameKey = "bench_press")))),
            ),
        )
        assertEquals("needs_answers", question["status"]!!.jsonPrimitive.content)
        val suggestion = (question["questions"] as JsonArray)[0].jsonObject["suggestions"]!!.jsonArray[0].jsonObject
        assertEquals("Bench press", suggestion["name"]!!.jsonPrimitive.content)
        assertEquals(obj("""{"key": "bench_press"}"""), suggestion["ref"])
    }

    @Test
    fun deleteRoutinesTakesAListOfIds() {
        assertEquals(Command.DeleteRoutines(listOf(id, "b")), decoded("delete_routines", """{"routine_ids": ["$id", "b"]}"""))
        fun reasons(body: String) = (CommandWire.decode("delete_routines", obj(body)) as WireDecoding.Invalid).reasons
        assertEquals(listOf("routine_ids is required"), reasons("{}"))
        assertEquals(listOf("routine_ids must be an array of ids"), reasons("""{"routine_ids": "$id"}"""))
        assertEquals(listOf("routine_ids[1] must be a string"), reasons("""{"routine_ids": ["$id", 3]}"""))
        val schema = checkNotNull(CommandWire.schema("delete_routines"))["properties"]!!.jsonObject["routine_ids"]!!.jsonObject
        assertEquals("array", schema["type"]!!.jsonPrimitive.content)
        assertEquals("$MAX_DELETE_ROUTINES", schema["maxItems"]!!.jsonPrimitive.content)
        assertEquals(
            obj("""{"status": "done", "ids": {"deletedCount": "2"}}"""),
            CommandWire.encode(CommandResult.Done(mapOf(CommandIds.DELETED_COUNT to "2"))),
        )
    }
}
