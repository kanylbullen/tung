package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.core.catalogExerciseId
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

suspend fun HttpClient.command(token: String, name: String, body: String = "{}"): HttpResponse =
    post("/api/v1/commands/$name") {
        bearerAuth(token)
        jsonBody(body)
    }

/** Runs a command and expects `done`; returns its ids. */
suspend fun HttpClient.done(token: String, name: String, body: String = "{}"): JsonObject {
    val response = command(token, name, body)
    assertEquals(HttpStatusCode.OK, response.status, "$name: ${response.bodyAsText()}")
    val result = response.json()
    assertEquals("done", result["status"]!!.str, "$name: $result")
    return result["ids"]!!.jsonObject
}

class CommandTest {

    @Test
    fun aWorkoutBuiltFromCommandsIsInTheHistory() = tungTest {
        val token = client.register()
        val workoutId = client.done(token, "start_workout")["workoutId"]!!.str
        assertEquals(workoutId, client.done(token, "start_workout")["workoutId"]!!.str, "one workout in progress: start returns it")

        val added = client.done(token, "add_exercise", """{"workout_id": "$workoutId", "exercise": {"name": "Bänkpress"}}""")
        assertEquals(BENCH, added["exerciseId"]!!.str, "a Swedish name resolves to the catalogue exercise")
        val weId = added["workoutExerciseId"]!!.str
        val firstSet = client.authedGet("/api/v1/workouts/$workoutId", token).workout().exercises.single().sets.single()

        client.done(token, "set_weight", """{"set_id": "${firstSet.id}", "weight_kg": 80}""")
        client.done(token, "set_reps", """{"set_id": "${firstSet.id}", "reps": 8}""")
        val logged = client.done(token, "log_set", """{"workout_id": "$workoutId"}""")
        assertEquals(firstSet.id, logged["setId"]!!.str, "without set_id the current set is logged")
        val addedSet = assertNotNull(logged["addedSetId"], "the last set of the exercise adds the next one").str

        val inProgress = client.authedGet("/api/v1/workouts/$workoutId", token).workout()
        val sets = inProgress.exercises.single { it.id == weId }.sets
        assertEquals(listOf(firstSet.id, addedSet), sets.map { it.id })
        assertEquals(listOf(true, false), sets.map { it.completed })
        assertEquals(80.0 to 8, sets[1].weightKg to sets[1].reps, "the added set copies the one just logged")

        assertEquals(workoutId, client.done(token, "finish_workout", """{"workout_id": "$workoutId"}""")["workoutId"]!!.str)
        val history = client.authedGet("/api/v1/workouts", token).json()["workouts"]!!.jsonArray
        val finished = ApiJson.decodeFromString(WorkoutDto.serializer(), history.single().toString())
        assertEquals(workoutId, finished.id)
        assertNotNull(finished.endedAt)
        assertEquals(listOf(80.0 to 8), finished.exercises.single().sets.map { it.weightKg to it.reps }, "the unticked set was dropped")

        // The next session pre-fills from this one, through /last's query.
        val next = client.done(token, "start_workout")["workoutId"]!!.str
        client.done(token, "add_exercise", """{"workout_id": "$next", "exercise": {"key": "bench_press"}}""")
        val prefilled = client.authedGet("/api/v1/workouts/$next", token).workout().exercises.single().sets.single()
        assertEquals(80.0 to 8, prefilled.weightKg to prefilled.reps)
    }

    @Test
    fun aChangeMadeByCommandIsPulledBySync() = tungTest {
        val token = client.register()
        val workoutId = client.done(token, "start_workout")["workoutId"]!!.str
        client.done(token, "add_exercise", """{"workout_id": "$workoutId", "exercise": {"key": "back_squat"}}""")
        val pulled = client.synced(token, cursor = 0)
        assertEquals(listOf(workoutId), pulled.changes.workouts.map { it.id })
        assertEquals(listOf(SQUAT), pulled.changes.workoutExercises.map { it.exerciseId })
        assertEquals(1, pulled.changes.sets.size)
    }

    @Test
    fun applyPlanWritesNothingUntilEveryNameResolves() = tungTest {
        val token = client.register()
        val unknown = client.command(
            token,
            "apply_plan",
            """{"exercises": [{"exercise": {"key": "deadlift"}, "set_count": 3, "reps": 5, "weight_kg": 140},
                              {"exercise": {"name": "Flying squirrel press"}, "set_count": 3}]}""",
        )
        assertEquals(HttpStatusCode.OK, unknown.status, unknown.bodyAsText())
        val result = unknown.json()
        assertEquals("needs_answers", result["status"]!!.str)
        val question = result["questions"]!!.jsonArray.single().jsonObject
        assertEquals("Flying squirrel press", question["text"]!!.str)
        assertTrue(question["suggestions"]!!.jsonArray.isNotEmpty(), "words it shares with real exercises bring suggestions")
        assertTrue(client.synced(token).changes.workouts.isEmpty(), "nothing was written, not even a workout")

        val ids = client.done(
            token,
            "apply_plan",
            """{"exercises": [{"exercise": {"key": "deadlift"}, "set_count": 3, "reps": 5, "weight_kg": 140},
                              {"exercise": "Bench press", "sets": [{"weight_kg": 60, "reps": 10}, {"reps": 8}]}]}""",
        )
        val workout = client.authedGet("/api/v1/workouts/${ids["workoutId"]!!.str}", token).workout()
        assertEquals(listOf(catalogExerciseId("deadlift"), BENCH), workout.exercises.map { it.exerciseId })
        assertEquals(List(3) { 140.0 to 5 }, workout.exercises[0].sets.map { it.weightKg to it.reps })
        assertEquals(listOf(60.0 to 10, 60.0 to 8), workout.exercises[1].sets.map { it.weightKg to it.reps }, "a missing weight comes from the set before")
        assertTrue(workout.exercises.flatMap { it.sets }.none { it.completed }, "a plan ticks nothing")
    }

    @Test
    fun aRoutineFromAPlanStartsWithItsTargetsAndIsNotPaddedPastThem() = tungTest {
        val token = client.register()
        val routineId = client.done(
            token,
            "save_routine_plan",
            """{"name": "Push A", "exercises": [{"exercise": {"key": "bench_press"}, "target_sets": 2, "target_reps": 8},
                                                 {"exercise": {"key": "overhead_press"}, "target_sets": 3}]}""",
        )["routineId"]!!.str
        val workoutId = client.done(token, "start_routine", """{"routine_id": "$routineId"}""")["workoutId"]!!.str
        val workout = client.authedGet("/api/v1/workouts/$workoutId", token).workout()
        assertEquals(routineId, workout.routineId)
        assertEquals(listOf(BENCH, catalogExerciseId("overhead_press")), workout.exercises.map { it.exerciseId })
        assertEquals(listOf(2, 3), workout.exercises.map { it.sets.size })
        assertEquals(listOf(0.0 to 8, 0.0 to 8), workout.exercises[0].sets.map { it.weightKg to it.reps }, "no history: 0 kg × target reps")

        val bench = workout.exercises[0].sets
        client.done(token, "set_weight", """{"set_id": "${bench[0].id}", "weight_kg": 60, "apply_to_open_sets": true}""")
        client.done(token, "log_set", """{"workout_id": "$workoutId", "set_id": "${bench[0].id}"}""")
        val last = client.done(token, "log_set", """{"workout_id": "$workoutId", "set_id": "${bench[1].id}"}""")
        assertNull(last["addedSetId"], "the routine's second set of two adds no third")
        val after = client.authedGet("/api/v1/workouts/$workoutId", token).workout().exercises[0].sets
        assertEquals(listOf(60.0, 60.0), after.map { it.weightKg }, "apply_to_open_sets wrote both")

        val routines = client.authedGet("/api/v1/export", token).json()["routines"]!!.jsonArray
        assertNotNull(routines.single().jsonObject["last_used_at"]?.str, "starting a routine marks it used")
    }

    @Test
    fun programsFollowTheAppsRules() = tungTest {
        val token = client.register()
        val routine = client.done(token, "save_routine_plan", """{"name": "Legs", "exercises": [{"exercise": {"key": "back_squat"}, "target_sets": 3}]}""")["routineId"]!!.str
        val first = client.done(token, "create_program", """{"name": "One", "mode": "fixed"}""")["programId"]!!.str
        val second = client.done(token, "create_program", """{"name": "Two", "mode": "rotating"}""")["programId"]!!.str
        val day1 = client.done(token, "add_program_day", """{"program_id": "$first", "routine_id": "$routine"}""")["programDayId"]!!.str
        val day2 = client.done(token, "add_program_day", """{"program_id": "$first", "routine_id": "$routine"}""")["programDayId"]!!.str
        client.done(token, "toggle_program_weekday", """{"day_id": "$day1", "weekday": 1}""")
        client.done(token, "toggle_program_weekday", """{"day_id": "$day1", "weekday": 3}""")
        client.done(token, "activate_program", """{"program_id": "$first"}""")
        client.done(token, "activate_program", """{"program_id": "$second"}""")
        client.done(token, "move_program_day", """{"day_id": "$day2", "delta": -1}""")

        val programs = ApiJson.decodeFromString(ExportDocument.serializer(), client.authedGet("/api/v1/export", token).bodyAsText()).programs
        assertEquals(listOf(false, true), programs.sortedBy { it.name }.map { it.isActive }, "one active program at a time")
        val days = programs.single { it.id == first }.days
        assertEquals(listOf(day2, day1), days.map { it.id })
        assertEquals(0b101, days.single { it.id == day1 }.weekdays, "Monday and Wednesday")

        client.done(token, "remove_program_day", """{"day_id": "$day2"}""")
        val renumbered = ApiJson.decodeFromString(ExportDocument.serializer(), client.authedGet("/api/v1/export", token).bodyAsText())
            .programs.single { it.id == first }.days
        assertEquals(listOf(0), renumbered.map { it.position }, "removing a day closes the gap")

        client.done(token, "delete_routine", """{"routine_id": "$routine"}""")
        val gone = ApiJson.decodeFromString(ExportDocument.serializer(), client.authedGet("/api/v1/export", token).bodyAsText())
        assertTrue(gone.routines.isEmpty())
        assertTrue(gone.programs.single { it.id == first }.days.isEmpty(), "a deleted routine takes its program days along")
    }

    @Test
    fun refusalsAndErrors() = tungTest {
        val token = client.register()
        val missing = client.command(token, "log_set", """{"workout_id": "${id()}"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, missing.status)
        assertEquals("rejected", missing.json()["status"]!!.str)

        val outOfRange = client.command(token, "set_reps", """{"set_id": "${id()}", "reps": 5000}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, outOfRange.status)
        assertTrue(outOfRange.bodyAsText().contains("reps must be between 0 and 999"), outOfRange.bodyAsText())

        val badBody = client.command(token, "set_reps", """{"set": "x"}""")
        assertEquals(HttpStatusCode.BadRequest, badBody.status)
        assertEquals("invalid_command", badBody.errorCode())

        assertEquals(HttpStatusCode.NotFound, client.command(token, "lift_heavy").status)

        val workoutId = client.done(token, "start_workout")["workoutId"]!!.str
        client.done(token, "add_exercise", """{"workout_id": "$workoutId", "exercise": {"key": "bench_press"}}""")
        val zeroReps = client.command(token, "log_set", """{"workout_id": "$workoutId"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, zeroReps.status, "a set without reps is not logged")

        val readToken = client.post("/api/v1/tokens") {
            bearerAuth(token)
            jsonBody("""{"name": "reader", "scope": "read"}""")
        }.json()["token"]!!.str
        val forbidden = client.command(readToken, "start_workout")
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        assertEquals("insufficient_scope", forbidden.errorCode())

        val other = client.register()
        val foreign = client.command(other, "finish_workout", """{"workout_id": "$workoutId"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, foreign.status, "another user's workout does not exist for them")
    }
}
