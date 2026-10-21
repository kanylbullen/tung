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
        assertNull(logged["addedSetId"], "log_set only ticks, even the exercise's last set (issue #64)")
        assertEquals(1, client.authedGet("/api/v1/workouts/$workoutId", token).workout().exercises.single().sets.size)
        val addedSet = client.done(token, "add_set", """{"workout_exercise_id": "$weId"}""")["setId"]!!.str

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
    fun logSetWithAddNextAddsACopyAfterTheLastSet() = tungTest {
        val token = client.register()
        val workoutId = client.done(token, "start_workout")["workoutId"]!!.str
        val weId = client.done(token, "add_exercise", """{"workout_id": "$workoutId", "exercise": {"key": "bench_press"}, "sets": 2}""")["workoutExerciseId"]!!.str
        val (first, second) = client.authedGet("/api/v1/workouts/$workoutId", token).workout().exercises.single().sets
        client.done(token, "set_weight", """{"set_id": "${first.id}", "weight_kg": 80, "apply_to_open_sets": true}""")
        client.done(token, "set_reps", """{"set_id": "${first.id}", "reps": 8, "apply_to_open_sets": true}""")
        val notLast = client.done(token, "log_set", """{"workout_id": "$workoutId", "set_id": "${first.id}", "add_next": true}""")
        assertNull(notLast["addedSetId"], "not the exercise's last set: nothing added")
        val last = client.done(token, "log_set", """{"workout_id": "$workoutId", "set_id": "${second.id}", "add_next": true}""")
        val added = assertNotNull(last["addedSetId"], "add_next after the last set adds the next one").str
        val sets = client.authedGet("/api/v1/workouts/$workoutId", token).workout().exercises.single { it.id == weId }.sets
        assertEquals(listOf(first.id, second.id, added), sets.map { it.id })
        assertEquals(listOf(true, true, false), sets.map { it.completed })
        assertEquals(80.0 to 8, sets[2].weightKg to sets[2].reps, "the added set copies the one just logged")
    }

    @Test
    fun anAddedExerciseGetsLastSessionsSetsOrTheCountAskedFor() = tungTest {
        val token = client.register()
        val workoutId = client.done(token, "start_workout")["workoutId"]!!.str
        suspend fun sets(id: String, weId: String) =
            client.authedGet("/api/v1/workouts/$id", token).workout().exercises.single { it.id == weId }.sets

        val weId = client.done(token, "add_exercise", """{"workout_id": "$workoutId", "exercise": {"key": "bench_press"}}""")["workoutExerciseId"]!!.str
        val first = sets(workoutId, weId)
        assertEquals(listOf(0.0 to 0), first.map { it.weightKg to it.reps }, "no history: one set of 0 x 0")

        client.done(token, "set_weight", """{"set_id": "${first[0].id}", "weight_kg": 60}""")
        client.done(token, "set_reps", """{"set_id": "${first[0].id}", "reps": 8}""")
        val added = client.done(token, "add_set", """{"workout_exercise_id": "$weId", "count": 2}""")["setId"]!!.str
        val three = sets(workoutId, weId)
        assertEquals(added, three[1].id, "add_set returns the first set it added")
        assertEquals(List(3) { 60.0 to 8 }, three.map { it.weightKg to it.reps }, "each added set copies the one before")
        client.done(token, "set_reps", """{"set_id": "${three[2].id}", "reps": 6}""")
        three.forEach { client.done(token, "tick_set", """{"set_id": "${it.id}", "done": true}""") }
        client.done(token, "finish_workout", """{"workout_id": "$workoutId"}""")

        val next = client.done(token, "start_workout")["workoutId"]!!.str
        val benchAgain = client.done(token, "add_exercise", """{"workout_id": "$next", "exercise": {"key": "bench_press"}}""")["workoutExerciseId"]!!.str
        assertEquals(
            listOf(60.0 to 8, 60.0 to 8, 60.0 to 6),
            sets(next, benchAgain).map { it.weightKg to it.reps },
            "as many sets as last session, each from its own position",
        )
        assertTrue(sets(next, benchAgain).none { it.completed })
        val five = client.done(token, "add_exercise", """{"workout_id": "$next", "exercise": {"key": "bench_press"}, "sets": 5}""")["workoutExerciseId"]!!.str
        assertEquals(listOf(60.0 to 8, 60.0 to 8, 60.0 to 6, 60.0 to 6, 60.0 to 6), sets(next, five).map { it.weightKg to it.reps })
        val squat = client.done(token, "add_exercise", """{"workout_id": "$next", "exercise": {"key": "back_squat"}, "sets": 4}""")["workoutExerciseId"]!!.str
        assertEquals(List(4) { 0.0 to 0 }, sets(next, squat).map { it.weightKg to it.reps })

        val tooMany = client.command(token, "add_exercise", """{"workout_id": "$next", "exercise": {"key": "deadlift"}, "sets": 21}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, tooMany.status)
        assertTrue(tooMany.bodyAsText().contains("sets must be between 1 and 20"), tooMany.bodyAsText())
        val none = client.command(token, "add_set", """{"workout_exercise_id": "$squat", "count": 0}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, none.status)
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
        client.done(token, "log_set", """{"workout_id": "$workoutId", "set_id": "${bench[0].id}", "add_next": true}""")
        val last = client.done(token, "log_set", """{"workout_id": "$workoutId", "set_id": "${bench[1].id}", "add_next": true}""")
        assertNull(last["addedSetId"], "with add_next, the routine's second set of two still adds no third")
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
    fun deleteRoutinesTakesSeveralInOneGo() = tungTest {
        val token = client.register()
        suspend fun export() = ApiJson.decodeFromString(ExportDocument.serializer(), client.authedGet("/api/v1/export", token).bodyAsText())
        suspend fun routine(name: String) = client.done(
            token,
            "save_routine_plan",
            """{"name": "$name", "exercises": [{"exercise": {"key": "bench_press"}, "target_sets": 1, "target_reps": 5}]}""",
        )["routineId"]!!.str
        val a = routine("A")
        val b = routine("B")
        val kept = routine("Kept")
        val program = client.done(token, "create_program", """{"name": "P", "mode": "rotating"}""")["programId"]!!.str
        val dayA = client.done(token, "add_program_day", """{"program_id": "$program", "routine_id": "$a"}""")["programDayId"]!!.str
        val dayKept = client.done(token, "add_program_day", """{"program_id": "$program", "routine_id": "$kept"}""")["programDayId"]!!.str
        val workoutId = client.done(token, "start_routine", """{"routine_id": "$a"}""")["workoutId"]!!.str
        client.done(token, "log_set", """{"workout_id": "$workoutId"}""")
        client.done(token, "finish_workout", """{"workout_id": "$workoutId"}""")
        val exercisesOfAB = export().routines.filter { it.id in setOf(a, b) }.flatMap { r -> r.exercises.map { it.id } }.toSet()

        // A duplicate counts once and an unknown id is skipped, as in the app.
        val ids = client.done(token, "delete_routines", """{"routine_ids": ["$a", "$b", "$a", "${id()}"]}""")
        assertEquals("2", ids["deletedCount"]!!.str)

        val after = export()
        assertEquals(listOf(kept), after.routines.map { it.id })
        assertEquals(listOf(dayKept), after.programs.single().days.map { it.id }, "A's program day goes with it")
        assertEquals(a, client.authedGet("/api/v1/workouts/$workoutId", token).workout().routineId, "history is kept")
        val changes = client.synced(token).changes
        assertEquals(setOf(a, b), changes.routines.filter { it.deletedAt != null }.map { it.id }.toSet())
        assertEquals(exercisesOfAB, changes.routineExercises.filter { it.deletedAt != null }.map { it.id }.toSet())
        assertEquals(setOf(dayA), changes.programDays.filter { it.deletedAt != null }.map { it.id }.toSet())

        // Naming only routines that are gone is a refusal, and so is an empty list.
        val none = client.command(token, "delete_routines", """{"routine_ids": ["$a", "${id()}"]}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, none.status)
        assertEquals("rejected", none.json()["status"]!!.str)
        assertEquals(HttpStatusCode.UnprocessableEntity, client.command(token, "delete_routines", """{"routine_ids": []}""").status)
        assertEquals(listOf(kept), export().routines.map { it.id })
    }

    @Test
    fun aSavedRoutineCanBeEdited() = tungTest {
        val token = client.register()
        suspend fun export() = ApiJson.decodeFromString(ExportDocument.serializer(), client.authedGet("/api/v1/export", token).bodyAsText())
        val routineId = client.done(
            token,
            "save_routine_plan",
            """{"name": "Push", "exercises": [{"exercise": {"key": "bench_press"}, "target_sets": 3, "target_reps": 8},
                                               {"exercise": {"key": "overhead_press"}, "target_sets": 3}]}""",
        )["routineId"]!!.str

        // Appended last, target sets 3 by default and no target reps.
        val added = client.done(token, "add_routine_exercise", """{"routine_id": "$routineId", "exercise": {"name": "Skivstångscurl"}}""")
        assertEquals(catalogExerciseId("barbell_curl"), added["exerciseId"]!!.str, "a Swedish name resolves")
        val curlId = added["routineExerciseId"]!!.str
        val withTargets = client.done(
            token,
            "add_routine_exercise",
            """{"routine_id": "$routineId", "exercise": {"key": "back_squat"}, "target_sets": 5, "target_reps": 5}""",
        )["routineExerciseId"]!!.str
        var exercises = export().routines.single().exercises.sortedBy { it.position }
        assertEquals(listOf(BENCH, catalogExerciseId("overhead_press"), catalogExerciseId("barbell_curl"), SQUAT), exercises.map { it.exerciseId })
        assertEquals(listOf(0, 1, 2, 3), exercises.map { it.position })
        assertEquals(3 to null, exercises[2].targetSets to exercises[2].targetReps)
        assertEquals(5 to 5, exercises[3].targetSets to exercises[3].targetReps)

        // An unknown name is a question and writes nothing.
        val unknown = client.command(token, "add_routine_exercise", """{"routine_id": "$routineId", "exercise": {"name": "Flying squirrel press"}}""")
        assertEquals("needs_answers", unknown.json()["status"]!!.str)
        assertEquals(4, export().routines.single().exercises.size)

        // Target reps: set, then cleared.
        client.done(token, "set_target_reps", """{"routine_exercise_id": "$curlId", "target_reps": 12}""")
        assertEquals(12, export().routines.single().exercises.single { it.id == curlId }.targetReps)
        client.done(token, "set_target_reps", """{"routine_exercise_id": "$curlId", "target_reps": null}""")
        assertNull(export().routines.single().exercises.single { it.id == curlId }.targetReps)

        // Removing the second exercise closes the gap and leaves a tombstone the phone pulls.
        val press = exercises[1].id
        client.done(token, "remove_routine_exercise", """{"routine_exercise_id": "$press"}""")
        exercises = export().routines.single().exercises.sortedBy { it.position }
        assertEquals(listOf(BENCH, catalogExerciseId("barbell_curl"), SQUAT), exercises.map { it.exerciseId })
        assertEquals(listOf(0, 1, 2), exercises.map { it.position }, "positions are 0, 1, 2 again")
        val tombstone = client.synced(token).changes.routineExercises.single { it.id == press }
        assertNotNull(tombstone.deletedAt, "a delete is a tombstone, not a DELETE")
        assertEquals(withTargets, exercises.last().id)

        // The started workout follows the edited routine.
        val workoutId = client.done(token, "start_routine", """{"routine_id": "$routineId"}""")["workoutId"]!!.str
        val workout = client.authedGet("/api/v1/workouts/$workoutId", token).workout()
        assertEquals(listOf(BENCH, catalogExerciseId("barbell_curl"), SQUAT), workout.exercises.map { it.exerciseId })
        assertEquals(listOf(3, 3, 5), workout.exercises.map { it.sets.size })

        // Gone ids and bad values are refused.
        assertEquals(HttpStatusCode.UnprocessableEntity, client.command(token, "remove_routine_exercise", """{"routine_exercise_id": "$press"}""").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, client.command(token, "set_target_reps", """{"routine_exercise_id": "${id()}", "target_reps": 8}""").status)
        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            client.command(token, "add_routine_exercise", """{"routine_id": "${id()}", "exercise": {"key": "bench_press"}}""").status,
        )
        val zero = client.command(token, "set_target_reps", """{"routine_exercise_id": "$curlId", "target_reps": 0}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, zero.status)
        assertTrue(zero.bodyAsText().contains("between 1 and 999"), zero.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, client.command(token, "set_target_reps", """{"routine_exercise_id": "$curlId"}""").status)
    }

    @Test
    fun aRoutineFromScratchStartsEmptyAndIsFilledByCommands() = tungTest {
        val token = client.register()
        suspend fun export() = ApiJson.decodeFromString(ExportDocument.serializer(), client.authedGet("/api/v1/export", token).bodyAsText())
        val routineId = client.done(token, "create_routine", """{"name": "  Legs "}""")["routineId"]!!.str
        val empty = export().routines.single()
        assertEquals(routineId to "Legs", empty.id to empty.name, "the name is trimmed")
        assertTrue(empty.exercises.isEmpty(), "a new routine has no exercises")
        assertNull(empty.lastUsedAt)
        assertEquals(routineId, client.synced(token).changes.routines.single().id, "sync carries the new routine")

        client.done(token, "add_routine_exercise", """{"routine_id": "$routineId", "exercise": {"key": "back_squat"}}""")
        val filled = export().routines.single().exercises.single()
        assertEquals(SQUAT to 0, filled.exerciseId to filled.position, "the first exercise goes first")

        val blank = client.command(token, "create_routine", """{"name": "  "}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, blank.status)
        assertTrue(blank.bodyAsText().contains("name must not be blank"), blank.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, client.command(token, "create_routine", "{}").status)
        assertEquals(1, export().routines.size, "a refused command writes nothing")
    }

    @Test
    fun aProgramsModeCanBeSwitchedAndKeepsItsDays() = tungTest {
        val token = client.register()
        suspend fun program() = ApiJson.decodeFromString(ExportDocument.serializer(), client.authedGet("/api/v1/export", token).bodyAsText()).programs.single()
        val routine = client.done(token, "save_routine_plan", """{"name": "Legs", "exercises": [{"exercise": {"key": "back_squat"}, "target_sets": 3}]}""")["routineId"]!!.str
        val programId = client.done(token, "create_program", """{"name": "PPL", "mode": "rotating"}""")["programId"]!!.str
        val day = client.done(token, "add_program_day", """{"program_id": "$programId", "routine_id": "$routine"}""")["programDayId"]!!.str
        client.done(token, "toggle_program_weekday", """{"day_id": "$day", "weekday": 2}""")
        val before = program().updatedAt

        client.done(token, "set_program_mode", """{"program_id": "$programId", "mode": "fixed"}""")
        val fixed = program()
        assertEquals("fixed", fixed.mode)
        assertTrue(fixed.updatedAt > before, "the change moves updated_at forward")
        assertEquals(listOf(day to 0b10), fixed.days.map { it.id to it.weekdays }, "days and weekdays are kept")
        assertTrue(client.synced(token).changes.programs.single().mode == "fixed", "sync carries the new mode")

        client.done(token, "set_program_mode", """{"program_id": "$programId", "mode": "fixed"}""")
        assertEquals(fixed.updatedAt, program().updatedAt, "the same mode again writes nothing")
        client.done(token, "set_program_mode", """{"program_id": "$programId", "mode": "rotating"}""")
        assertEquals("rotating", program().mode)

        assertEquals(HttpStatusCode.BadRequest, client.command(token, "set_program_mode", """{"program_id": "$programId", "mode": "weekly"}""").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, client.command(token, "set_program_mode", """{"program_id": "${id()}", "mode": "fixed"}""").status)
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
