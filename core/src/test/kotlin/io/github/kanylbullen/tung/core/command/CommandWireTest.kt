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
        "rename_routine" to """{"routine_id": "$id", "name": "Pull"}""",
        "delete_routine" to """{"routine_id": "$id"}""",
        "move_routine_exercise" to """{"routine_exercise_id": "$id", "delta": -1}""",
        "adjust_target_sets" to """{"routine_exercise_id": "$id", "delta": 1}""",
        "create_program" to """{"name": "PPL", "mode": "rotating"}""",
        "rename_program" to """{"program_id": "$id", "name": "PPL 2"}""",
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
}
