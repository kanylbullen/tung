package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.sync.SYNC_MAX_ROWS
import io.github.kanylbullen.tung.core.sync.SYNC_PATH
import io.github.kanylbullen.tung.core.sync.SyncChanges
import io.github.kanylbullen.tung.core.sync.SyncExercise
import io.github.kanylbullen.tung.core.sync.SyncProgram
import io.github.kanylbullen.tung.core.sync.SyncProgramDay
import io.github.kanylbullen.tung.core.sync.SyncRequest
import io.github.kanylbullen.tung.core.sync.SyncResponse
import io.github.kanylbullen.tung.core.sync.SyncRoutine
import io.github.kanylbullen.tung.core.sync.SyncRoutineExercise
import io.github.kanylbullen.tung.core.sync.SyncSet
import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.github.kanylbullen.tung.core.sync.SyncWorkoutExercise
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

suspend fun HttpClient.syncRaw(token: String, body: String): HttpResponse =
    post(SYNC_PATH) {
        bearerAuth(token)
        jsonBody(body)
    }

suspend fun HttpClient.sync(token: String, cursor: Long = 0, changes: SyncChanges = SyncChanges()): HttpResponse =
    syncRaw(token, ApiJson.encodeToString(SyncRequest.serializer(), SyncRequest(cursor, changes)))

/** Syncs and expects 200. */
suspend fun HttpClient.synced(token: String, cursor: Long = 0, changes: SyncChanges = SyncChanges()): SyncResponse {
    val response = sync(token, cursor, changes)
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return ApiJson.decodeFromString(SyncResponse.serializer(), response.bodyAsText())
}

suspend fun HttpClient.login(email: String, password: String = "correct horse battery"): String {
    val response = post("/api/v1/auth/login") { jsonBody("""{"email": "$email", "password": "$password"}""") }
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return response.json()["token"]!!.str
}

/** One row of every table, wired together: a custom exercise, a workout using it and bench, a routine, a program. */
class Graph(at: Long = 1_000) {
    val custom = SyncExercise(id(), null, "Sled push", "legs", "other", true, 120, at)
    val benchCopy = SyncExercise(BENCH, "bench_press", "Bench press", "chest", "barbell", false, 150, at)
    val workout = SyncWorkout(id(), 1_700_000_000_000, 1_700_003_600_000, "Heavy day", null, null, at)
    val benchRow = SyncWorkoutExercise(id(), workout.id, BENCH, 0, at)
    val sledRow = SyncWorkoutExercise(id(), workout.id, custom.id, 1, at)
    val sets = listOf(
        SyncSet(id(), benchRow.id, 0, 80.0, 8, true, at),
        SyncSet(id(), benchRow.id, 1, 82.5, 6, true, at),
        SyncSet(id(), sledRow.id, 0, 40.0, 20, false, at),
    )
    val routine = SyncRoutine(id(), "Push", 1_700_000_000_000, at)
    val routineExercise = SyncRoutineExercise(id(), routine.id, BENCH, 0, 3, 8, at)
    val program = SyncProgram(id(), "PPL", "fixed", true, at)
    val day = SyncProgramDay(id(), program.id, routine.id, 0, 0b101, at)

    val changes = SyncChanges(
        exercises = listOf(custom, benchCopy),
        workouts = listOf(workout),
        workoutExercises = listOf(benchRow, sledRow),
        sets = sets,
        routines = listOf(routine),
        routineExercises = listOf(routineExercise),
        programs = listOf(program),
        programDays = listOf(day),
    )
}

private fun SyncChanges.sorted() = SyncChanges(
    exercises.sortedBy { it.id }, workouts.sortedBy { it.id }, workoutExercises.sortedBy { it.id }, sets.sortedBy { it.id },
    routines.sortedBy { it.id }, routineExercises.sortedBy { it.id }, programs.sortedBy { it.id }, programDays.sortedBy { it.id },
)

class SyncTest {

    @Test
    fun aPushFromOneClientIsPulledByAnother() = tungTest {
        val email = uniqueEmail()
        val a = client.register(email)
        val b = client.login(email)
        val graph = Graph()

        val pushed = client.synced(a, 0, graph.changes)
        assertEquals(graph.changes.sorted(), pushed.changes.sorted(), "the pusher gets its own rows back, as stored")
        assertTrue(pushed.cursor > 0)

        val pulled = client.synced(b, 0)
        assertEquals(graph.changes.sorted(), pulled.changes.sorted())
        assertEquals(pushed.cursor, pulled.cursor)

        val again = client.synced(b, pulled.cursor)
        assertTrue(again.changes.isEmpty(), "nothing new: ${again.changes}")
        assertEquals(pulled.cursor, again.cursor, "the cursor stays when nothing changed")

        // The raw response is snake_case with nulls written out.
        val raw = client.sync(b, 0).bodyAsText()
        assertTrue("\"workout_exercises\":[" in raw && "\"weight_kg\":80.0" in raw && "\"deleted_at\":null" in raw, raw)
    }

    @Test
    fun anOlderOrEqualPushIsIgnoredAndTheStoredRowComesBack() = tungTest {
        val token = client.register()
        val graph = Graph(at = 2_000)
        val first = client.synced(token, 0, graph.changes)
        val set = graph.sets[0]

        val older = client.synced(token, first.cursor, SyncChanges(sets = listOf(set.copy(weightKg = 50.0, updatedAt = 1_000))))
        assertEquals(listOf(set), older.changes.sets, "the stored, newer row comes back so the client converges")
        assertEquals(first.cursor, older.cursor, "nothing was written")

        val equal = client.synced(token, first.cursor, SyncChanges(sets = listOf(set.copy(weightKg = 60.0))))
        assertEquals(listOf(set), equal.changes.sets, "equal updated_at is ignored too")

        val newer = client.synced(token, first.cursor, SyncChanges(sets = listOf(set.copy(weightKg = 90.0, updatedAt = 3_000))))
        assertEquals(90.0, newer.changes.sets.single().weightKg)
        assertTrue(newer.cursor > first.cursor)
        assertEquals(listOf(set.id), newer.changes.sets.map { it.id }, "only what changed since the cursor")
        assertEquals(1, newer.changes.size())
    }

    @Test
    fun tombstonesWinOverOlderUpdatesAndLoseToNewerOnes() = tungTest {
        val token = client.register()
        val graph = Graph(at = 1_000)
        client.synced(token, 0, graph.changes)
        val (deleted, kept) = graph.sets

        // A tombstone newer than the stored row deletes it and keeps the row, with its last values.
        val tomb = client.synced(token, 0, SyncChanges(sets = listOf(SyncSet(deleted.id, updatedAt = 2_000, deletedAt = 2_000))))
        val stored = tomb.changes.sets.single { it.id == deleted.id }
        assertEquals(2_000L, stored.deletedAt)
        assertEquals(deleted.copy(updatedAt = 2_000, deletedAt = 2_000), stored)

        // An older update does not bring it back.
        val stale = client.synced(token, 0, SyncChanges(sets = listOf(deleted.copy(reps = 12, updatedAt = 1_500))))
        assertEquals(stored, stale.changes.sets.single { it.id == deleted.id })

        // A newer update does: the row is live again with the new values.
        val revived = client.synced(token, 0, SyncChanges(sets = listOf(deleted.copy(reps = 12, updatedAt = 3_000))))
        assertNull(revived.changes.sets.single { it.id == deleted.id }.deletedAt)
        assertEquals(12, revived.changes.sets.single { it.id == deleted.id }.reps)

        // An older tombstone loses to a newer update.
        client.synced(token, 0, SyncChanges(sets = listOf(kept.copy(reps = 10, updatedAt = 5_000))))
        val lost = client.synced(token, 0, SyncChanges(sets = listOf(SyncSet(kept.id, updatedAt = 4_000, deletedAt = 4_000))))
        assertNull(lost.changes.sets.single { it.id == kept.id }.deletedAt)
        assertEquals(10, lost.changes.sets.single { it.id == kept.id }.reps)

        // The REST API no longer shows the deleted workout once it is tombstoned too.
        client.synced(token, 0, SyncChanges(workouts = listOf(SyncWorkout(graph.workout.id, updatedAt = 9_000, deletedAt = 9_000))))
        assertEquals(HttpStatusCode.NotFound, client.authedGet("/api/v1/workouts/${graph.workout.id}", token).status)

        // A tombstone for a row the server never had is dropped: nothing to delete.
        val unknown = id()
        val nothing = client.synced(token, 0, SyncChanges(routines = listOf(SyncRoutine(unknown, updatedAt = 1, deletedAt = 1))))
        assertTrue(nothing.changes.routines.none { it.id == unknown })
    }

    @Test
    fun theCursorAdvancesMonotonicallyAcrossSyncAndRestWrites() = tungTest {
        val token = client.register()
        val graph = Graph()
        var cursor = client.synced(token, 0, graph.changes).cursor
        fun advance(next: Long) {
            assertTrue(next > cursor, "cursor $next after $cursor")
            cursor = next
        }

        // Every REST write path stamps seq, so a pull sees it.
        val workoutId = id()
        assertEquals(HttpStatusCode.OK, client.put(token, workoutId, workout(1_700_100_000_000, exercise(SQUAT, 0, set(0, 100.0, 5)), endedAt = null)).status)
        var pull = client.synced(token, cursor)
        assertEquals(listOf(workoutId), pull.changes.workouts.map { it.id })
        assertEquals(1, pull.changes.workoutExercises.size)
        assertEquals(1, pull.changes.sets.size)
        advance(pull.cursor)

        assertEquals(HttpStatusCode.OK, client.post("/api/v1/workouts/$workoutId/finish") { bearerAuth(token) }.status)
        pull = client.synced(token, cursor)
        assertNotNull(pull.changes.workouts.single().endedAt)
        advance(pull.cursor)

        client.patch("/api/v1/exercises/${catalogExerciseId("deadlift")}") { bearerAuth(token); jsonBody("""{"rest_seconds": 240}""") }
        pull = client.synced(token, cursor)
        assertEquals(240, pull.changes.exercises.single().restSeconds)
        advance(pull.cursor)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/workouts/$workoutId") { bearerAuth(token) }.status)
        pull = client.synced(token, cursor)
        assertNotNull(pull.changes.workouts.single().deletedAt)
        assertTrue(pull.changes.sets.all { it.deletedAt != null })
        advance(pull.cursor)

        // A pull with nothing new keeps the cursor; a push moves it again.
        assertEquals(cursor, client.synced(token, cursor).cursor)
        advance(client.synced(token, cursor, SyncChanges(routines = listOf(graph.routine.copy(name = "Push A", updatedAt = 2_000)))).cursor)
    }

    @Test
    fun anotherUserSeesNothingAndKeepsTheirOwnRowsUnderTheSameIds() = tungTest {
        val owner = client.register()
        val other = client.register()
        val graph = Graph()
        client.synced(owner, 0, graph.changes)

        val pulled = client.synced(other, 0)
        assertTrue(pulled.changes.isEmpty(), "${pulled.changes}")

        // The same ids from another user are another user's rows.
        val theirs = graph.routine.copy(name = "Not yours", updatedAt = 5_000)
        client.synced(other, 0, SyncChanges(routines = listOf(theirs)))
        assertEquals(graph.routine, client.synced(owner, 0).changes.routines.single())
        assertEquals(theirs, client.synced(other, 0).changes.routines.single())
    }

    @Test
    fun childrenMayComeBeforeTheirParents() = tungTest {
        val token = client.register()
        val graph = Graph()
        // Children first in the JSON; the server writes parents first and checks foreign keys at commit.
        val c = graph.changes
        val body = """
            {"changes": {
              "program_days": ${ApiJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(SyncProgramDay.serializer()), c.programDays)},
              "sets": ${ApiJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(SyncSet.serializer()), c.sets)},
              "routine_exercises": ${ApiJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(SyncRoutineExercise.serializer()), c.routineExercises)},
              "workout_exercises": ${ApiJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(SyncWorkoutExercise.serializer()), c.workoutExercises)},
              "programs": ${ApiJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(SyncProgram.serializer()), c.programs)},
              "routines": ${ApiJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(SyncRoutine.serializer()), c.routines)},
              "workouts": ${ApiJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(SyncWorkout.serializer()), c.workouts)},
              "exercises": ${ApiJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(SyncExercise.serializer()), c.exercises)}
            }, "cursor": 0}
        """.trimIndent()
        val response = client.syncRaw(token, body)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(c.sorted(), ApiJson.decodeFromString(SyncResponse.serializer(), response.bodyAsText()).changes.sorted())

        // A live child of a tombstoned parent is fine: the parent row still exists.
        val dead = SyncWorkout(graph.workout.id, updatedAt = 2_000, deletedAt = 2_000)
        val lateRow = SyncWorkoutExercise(id(), graph.workout.id, SQUAT, 2, 2_000)
        client.synced(token, 0, SyncChanges(workouts = listOf(dead), workoutExercises = listOf(lateRow)))
    }

    @Test
    fun aChildWithoutItsParentIsRejectedAndNothingIsWritten() = tungTest {
        val token = client.register()
        val graph = Graph()
        val orphan = SyncSet(id(), id(), 0, 50.0, 5, true, 1_000)
        val response = client.sync(token, 0, graph.changes.copy(sets = graph.sets + orphan))
        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        assertEquals("missing_parent", response.errorCode())
        assertTrue(orphan.id in response.bodyAsText() && orphan.workoutExerciseId!! in response.bodyAsText(), response.bodyAsText())
        assertTrue(client.synced(token, 0).changes.isEmpty(), "the whole request was rolled back")

        // An exercise that is neither the catalogue's nor the user's is a missing parent too.
        val unknownExercise = id()
        val noExercise = client.sync(
            token, 0,
            SyncChanges(routines = listOf(graph.routine), routineExercises = listOf(graph.routineExercise.copy(exerciseId = unknownExercise))),
        )
        assertEquals(HttpStatusCode.Conflict, noExercise.status)
        assertTrue(unknownExercise in noExercise.bodyAsText(), noExercise.bodyAsText())

        // Another user's exercise does not count.
        val other = client.register()
        client.synced(other, 0, SyncChanges(exercises = listOf(graph.custom)))
        val theirs = client.sync(token, 0, SyncChanges(workouts = listOf(graph.workout), workoutExercises = listOf(graph.sledRow)))
        assertEquals(HttpStatusCode.Conflict, theirs.status)
    }

    @Test
    fun invalidRowsAreAllNamedAndNothingIsWritten() = tungTest {
        val token = client.register()
        val graph = Graph()
        val heavy = graph.sets[0].copy(weightKg = 1_000.0)
        val many = graph.sets[1].copy(reps = -1)
        val badRoutine = graph.routineExercise.copy(targetSets = 0)
        val noName = graph.program.copy(name = null)
        val response = client.sync(
            token, 0,
            graph.changes.copy(sets = listOf(heavy, many, graph.sets[2]), routineExercises = listOf(badRoutine), programs = listOf(noName)),
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_rows", response.errorCode())
        val message = response.bodyAsText()
        for (bad in listOf(heavy.id, many.id, badRoutine.id, noName.id)) assertTrue(bad in message, "$bad not named in $message")
        assertTrue(graph.sets[2].id !in message)
        assertTrue("weight" in message && "reps" in message && "target_sets" in message && "name" in message, message)
        assertTrue(client.synced(token, 0).changes.isEmpty())

        // Duplicate ids in one table and a negative cursor are refused as well.
        assertEquals(HttpStatusCode.BadRequest, client.sync(token, 0, SyncChanges(routines = listOf(graph.routine, graph.routine))).status)
        assertEquals(HttpStatusCode.BadRequest, client.sync(token, -1).status)
        assertEquals(HttpStatusCode.BadRequest, client.syncRaw(token, """{"changes": {"sets": [{"id": 1}]}}""").status)
    }

    @Test
    fun aCatalogueExerciseBecomesTheUsersCopy() = tungTest {
        val token = client.register()
        val other = client.register()
        val pushed = SyncExercise(BENCH, "renamed", "Hacked", "legs", "machine", true, 60, 1_000)
        val stored = client.synced(token, 0, SyncChanges(exercises = listOf(pushed))).changes.exercises.single()
        assertEquals(SyncExercise(BENCH, "bench_press", "Bench press", "chest", "barbell", false, 60, 1_000), stored)

        val mine = ApiJson.decodeFromString(ExerciseList.serializer(), client.authedGet("/api/v1/exercises", token).bodyAsText()).exercises
        assertEquals(60, mine.single { it.id == BENCH }.restSeconds)
        val theirs = ApiJson.decodeFromString(ExerciseList.serializer(), client.authedGet("/api/v1/exercises", other).bodyAsText()).exercises
        assertNull(theirs.single { it.id == BENCH }.restSeconds, "the catalogue row itself is untouched")

        // A tombstoned copy can be set again through PATCH.
        client.synced(token, 0, SyncChanges(exercises = listOf(SyncExercise(BENCH, updatedAt = 2_000, deletedAt = 2_000))))
        val patched = client.patch("/api/v1/exercises/$BENCH") { bearerAuth(token); jsonBody("""{"rest_seconds": 90}""") }
        assertEquals(HttpStatusCode.OK, patched.status, patched.bodyAsText())
        assertNull(client.synced(token, 0).changes.exercises.single { it.id == BENCH }.deletedAt)
    }

    @Test
    fun aReadTokenMayPullButNotPush() = tungTest {
        val session = client.register()
        val read = client.post("/api/v1/tokens") { bearerAuth(session); jsonBody("""{"name": "watch", "scope": "read"}""") }.json()["token"]!!.str
        val graph = Graph()
        client.synced(session, 0, graph.changes)

        assertEquals(graph.changes.size(), client.synced(read, 0).changes.size())
        val push = client.sync(read, 0, SyncChanges(routines = listOf(graph.routine.copy(updatedAt = 9_000))))
        assertEquals(HttpStatusCode.Forbidden, push.status)
        assertEquals("insufficient_scope", push.errorCode())
        assertEquals(HttpStatusCode.Unauthorized, client.syncRaw("tung_nope", "{}").status)
    }

    @Test
    fun tooManyRowsOrTooLargeABodyIs413() = tungTest {
        val token = client.register()
        val workoutExerciseId = id()
        val sets = List(SYNC_MAX_ROWS + 1) { SyncSet(id(), workoutExerciseId, it, 1.0, 1, true, 1) }
        val many = client.sync(token, 0, SyncChanges(sets = sets))
        assertEquals(HttpStatusCode.PayloadTooLarge, many.status)
        assertEquals("too_many_rows", many.errorCode())

        val huge = client.syncRaw(token, """{"cursor": 0, "padding": "${"x".repeat(9 * 1024 * 1024)}"}""")
        assertEquals(HttpStatusCode.PayloadTooLarge, huge.status)
        assertEquals("payload_too_large", huge.errorCode())
    }

    @Test
    fun tenThousandSetsInOneRequest() = tungTest {
        val email = uniqueEmail()
        val a = client.register(email)
        val b = client.login(email)
        val workout = SyncWorkout(id(), 1_700_000_000_000, null, null, null, null, 1_000)
        val rows = List(100) { SyncWorkoutExercise(id(), workout.id, BENCH, it, 1_000) }
        val sets = rows.flatMap { row -> List(100) { SyncSet(id(), row.id, it, 60.0 + it % 10, 5, true, 1_000) } }
        val changes = SyncChanges(workouts = listOf(workout), workoutExercises = rows, sets = sets)
        assertEquals(10_101, changes.size())

        val started = System.nanoTime()
        val pushed = client.synced(a, 0, changes)
        val pushMillis = (System.nanoTime() - started) / 1_000_000
        assertEquals(10_000, pushed.changes.sets.size)

        val pullStarted = System.nanoTime()
        val pulled = client.synced(b, 0)
        val pullMillis = (System.nanoTime() - pullStarted) / 1_000_000
        assertEquals(10_101, pulled.changes.size())
        println("SYNC-TIMING push of ${changes.size()} rows: $pushMillis ms; pull of ${pulled.changes.size()} rows: $pullMillis ms")
        assertTrue(pushMillis < 10_000, "push took $pushMillis ms")
    }
}
