package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.core.catalogExerciseId
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

val BENCH = catalogExerciseId("bench_press")
val SQUAT = catalogExerciseId("back_squat")

fun set(position: Int, weightKg: Double, reps: Int, completed: Boolean = true, updatedAt: Long? = 1_000L, id: String = id()) =
    SetInput(id, position, weightKg, reps, completed, updatedAt)

fun exercise(exerciseId: String, position: Int, vararg sets: SetInput, updatedAt: Long? = 1_000L, id: String = id()) =
    WorkoutExerciseInput(id, exerciseId, position, updatedAt, sets.toList())

fun workout(startedAt: Long, vararg exercises: WorkoutExerciseInput, endedAt: Long? = startedAt + 3_600_000, updatedAt: Long? = 1_000L, note: String? = null) =
    WorkoutInput(startedAt = startedAt, endedAt = endedAt, note = note, updatedAt = updatedAt, exercises = exercises.toList())

suspend fun HttpClient.put(token: String, workoutId: String, input: WorkoutInput): HttpResponse =
    putWorkout(token, workoutId, ApiJson.encodeToString(WorkoutInput.serializer(), input))

suspend fun HttpResponse.workout(): WorkoutDto = ApiJson.decodeFromString(WorkoutDto.serializer(), bodyAsText())

class WorkoutTest {

    @Test
    fun putThenGetRoundtripsTheNestedWorkout() = tungTest {
        val token = client.register()
        val workoutId = id()
        val input = workout(
            startedAt = 1_700_000_000_000,
            exercise(BENCH, 0, set(0, 80.0, 8), set(1, 82.5, 6), set(2, 85.0, 0, completed = false)),
            exercise(SQUAT, 1, set(0, 100.0, 5)),
            note = "Felt strong",
        )
        val put = client.put(token, workoutId, input)
        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())

        val got = client.authedGet("/api/v1/workouts/$workoutId", token)
        assertEquals(HttpStatusCode.OK, got.status)
        val stored = got.workout()
        assertEquals(workoutId, stored.id)
        assertEquals("Felt strong", stored.note)
        assertEquals(listOf(BENCH, SQUAT), stored.exercises.map { it.exerciseId })
        assertEquals(input.exercises.map { it.id }, stored.exercises.map { it.id }, "the client's ids are kept")
        assertEquals(listOf(80.0 to 8, 82.5 to 6, 85.0 to 0), stored.exercises[0].sets.map { it.weightKg to it.reps })
        assertEquals(listOf(true, true, false), stored.exercises[0].sets.map { it.completed })
        assertEquals(input.exercises[0].sets.map { it.id }, stored.exercises[0].sets.map { it.id })
        assertEquals(stored, put.workout(), "PUT answers with what GET returns")

        // The wire format is snake_case.
        val raw = got.bodyAsText()
        assertTrue("\"weight_kg\":80.0" in raw && "\"started_at\":1700000000000" in raw, raw)
    }

    @Test
    fun anOlderUpdatedAtDoesNotOverwriteANewerOne() = tungTest {
        val token = client.register()
        val workoutId = id()
        val setId = id()
        val exerciseRowId = id()
        val newer = workout(
            1_700_000_000_000,
            exercise(BENCH, 0, set(0, 100.0, 5, updatedAt = 2_000, id = setId), updatedAt = 2_000, id = exerciseRowId),
            updatedAt = 2_000,
            note = "newer",
        )
        assertEquals(HttpStatusCode.OK, client.put(token, workoutId, newer).status)

        val stale = workout(
            1_700_000_000_000,
            exercise(BENCH, 0, set(0, 60.0, 12, updatedAt = 1_500, id = setId), updatedAt = 1_500, id = exerciseRowId),
            updatedAt = 1_500,
            note = "older",
        )
        val afterStale = client.put(token, workoutId, stale).workout()
        assertEquals("newer", afterStale.note)
        assertEquals(100.0, afterStale.exercises.single().sets.single().weightKg)
        assertEquals(2_000, afterStale.updatedAt)

        // Equal is not newer either: replaying the same request changes nothing.
        val replay = client.put(token, workoutId, newer.copy(note = "same time")).workout()
        assertEquals("newer", replay.note)

        // A newer set wins on its own row even when the workout row is older.
        val newerSetOnly = stale.copy(
            exercises = listOf(exercise(BENCH, 0, set(0, 105.0, 5, updatedAt = 3_000, id = setId), updatedAt = 1_500, id = exerciseRowId)),
        )
        val merged = client.put(token, workoutId, newerSetOnly).workout()
        assertEquals("newer", merged.note)
        assertEquals(105.0, merged.exercises.single().sets.single().weightKg)
    }

    @Test
    fun rowsLeftOutOfANewerPutAreDeletedButNewerOnesStay() = tungTest {
        val token = client.register()
        val workoutId = id()
        val keep = set(0, 80.0, 8, updatedAt = 1_000)
        val drop = set(1, 80.0, 8, updatedAt = 1_000)
        val late = set(2, 80.0, 8, updatedAt = 5_000)
        val bench = exercise(BENCH, 0, keep, drop, late, updatedAt = 1_000)
        val squat = exercise(SQUAT, 1, set(0, 100.0, 5), updatedAt = 1_000)
        client.put(token, workoutId, workout(1_700_000_000_000, bench, squat, updatedAt = 1_000))

        val edited = client.put(
            token, workoutId,
            workout(1_700_000_000_000, bench.copy(sets = listOf(keep)), updatedAt = 2_000),
        ).workout()
        assertEquals(listOf(BENCH), edited.exercises.map { it.exerciseId }, "the squat row was older than the edit")
        assertEquals(listOf(keep.id, late.id), edited.exercises.single().sets.map { it.id }, "the set written after the edit stays")
    }

    @Test
    fun unknownExercisesAndBadNumbersAreRejected() = tungTest {
        val token = client.register()
        val unknown = client.put(token, id(), workout(1_000, exercise(id(), 0, set(0, 50.0, 5))))
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        assertEquals("unknown_exercise", unknown.errorCode())

        val tooHeavy = client.put(token, id(), workout(1_000, exercise(BENCH, 0, set(0, 1000.0, 5))))
        assertEquals(HttpStatusCode.BadRequest, tooHeavy.status)
        val negativeReps = client.put(token, id(), workout(1_000, exercise(BENCH, 0, set(0, 50.0, -1))))
        assertEquals(HttpStatusCode.BadRequest, negativeReps.status)
        val badId = client.putWorkout(token, "not-a-uuid", """{"started_at": 1000}""")
        assertEquals(HttpStatusCode.BadRequest, badId.status)
        assertEquals("invalid_id", badId.errorCode())
        val mismatch = client.putWorkout(token, id(), """{"id": "${id()}", "started_at": 1000}""")
        assertEquals(HttpStatusCode.BadRequest, mismatch.status)
    }

    @Test
    fun anotherUsersWorkoutIsNotFound() = tungTest {
        val alice = client.register()
        val bob = client.register()
        val workoutId = id()
        client.put(alice, workoutId, workout(1_700_000_000_000, exercise(BENCH, 0, set(0, 80.0, 8)), note = "alice"))

        assertEquals(HttpStatusCode.NotFound, client.authedGet("/api/v1/workouts/$workoutId", bob).status)
        assertEquals("not_found", client.authedGet("/api/v1/workouts/$workoutId", bob).errorCode())
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/workouts/$workoutId") { bearerAuth(bob) }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/workouts/$workoutId/finish") { bearerAuth(bob) }.status)
        assertFalse(workoutId in client.authedGet("/api/v1/workouts", bob).bodyAsText())

        // Bob writing the same id makes his own row; Alice's is untouched.
        val bobs = client.put(bob, workoutId, workout(1_700_000_000_000, note = "bob", updatedAt = 9_999)).workout()
        assertEquals("bob", bobs.note)
        assertEquals("alice", client.authedGet("/api/v1/workouts/$workoutId", alice).workout().note)
    }

    @Test
    fun listIsNewestFirstAndPages() = tungTest {
        val token = client.register()
        val ids = (1..5).map { n -> id().also { client.put(token, it, workout(1_700_000_000_000 + n * 86_400_000L, exercise(BENCH, 0, set(0, 50.0 + n, 5)))) } }

        val first = ApiJson.decodeFromString(WorkoutPage.serializer(), client.authedGet("/api/v1/workouts?limit=2", token).bodyAsText())
        assertEquals(listOf(ids[4], ids[3]), first.workouts.map { it.id })
        assertEquals(55.0, first.workouts[0].exercises.single().sets.single().weightKg, "nested sets are included")
        val second = ApiJson.decodeFromString(
            WorkoutPage.serializer(),
            client.authedGet("/api/v1/workouts?limit=2&before=${first.nextBefore}", token).bodyAsText(),
        )
        assertEquals(listOf(ids[2], ids[1]), second.workouts.map { it.id })
        val last = ApiJson.decodeFromString(
            WorkoutPage.serializer(),
            client.authedGet("/api/v1/workouts?limit=2&before=${second.nextBefore}", token).bodyAsText(),
        )
        assertEquals(listOf(ids[0]), last.workouts.map { it.id })
        assertNull(last.nextBefore)

        assertEquals(HttpStatusCode.BadRequest, client.authedGet("/api/v1/workouts?limit=0", token).status)
    }

    @Test
    fun deleteTombstonesAndAnOlderPutDoesNotResurrect() = tungTest {
        val token = client.register()
        val workoutId = id()
        val input = workout(1_700_000_000_000, exercise(BENCH, 0, set(0, 80.0, 8)), updatedAt = 1_000)
        client.put(token, workoutId, input)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/workouts/$workoutId") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.authedGet("/api/v1/workouts/$workoutId", token).status)
        assertFalse(workoutId in client.authedGet("/api/v1/workouts", token).bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/workouts/$workoutId") { bearerAuth(token) }.status)

        val stale = client.put(token, workoutId, input)
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals("workout_deleted", stale.errorCode())

        val restored = client.put(token, workoutId, input.copy(updatedAt = System.currentTimeMillis() + 60_000))
        assertEquals(HttpStatusCode.OK, restored.status)
    }

    @Test
    fun finishDropsUnloggedSetsAndDiscardsAnEmptyWorkout() = tungTest {
        val token = client.register()
        val workoutId = id()
        val logged = set(0, 80.0, 8)
        client.put(
            token, workoutId,
            workout(
                1_700_000_000_000,
                exercise(BENCH, 0, logged, set(1, 80.0, 8, completed = false), set(2, 80.0, 0, completed = true)),
                exercise(SQUAT, 1, set(0, 100.0, 5, completed = false)),
                endedAt = null,
            ),
        )
        val finished = client.post("/api/v1/workouts/$workoutId/finish") {
            bearerAuth(token)
            jsonBody("""{"ended_at": 1700003600000}""")
        }
        assertEquals(HttpStatusCode.OK, finished.status, finished.bodyAsText())
        val result = ApiJson.decodeFromString(FinishResult.serializer(), finished.bodyAsText())
        assertFalse(result.discarded)
        val saved = result.workout!!
        assertEquals(1_700_003_600_000, saved.endedAt)
        assertEquals(listOf(BENCH), saved.exercises.map { it.exerciseId })
        assertEquals(listOf(logged.id), saved.exercises.single().sets.map { it.id })

        val emptyId = id()
        client.put(token, emptyId, workout(1_700_100_000_000, exercise(BENCH, 0, set(0, 80.0, 8, completed = false)), endedAt = null))
        val discarded = ApiJson.decodeFromString(
            FinishResult.serializer(),
            client.post("/api/v1/workouts/$emptyId/finish") { bearerAuth(token) }.bodyAsText(),
        )
        assertTrue(discarded.discarded)
        assertNull(discarded.workout)
        assertEquals(HttpStatusCode.NotFound, client.authedGet("/api/v1/workouts/$emptyId", token).status)
    }
}
