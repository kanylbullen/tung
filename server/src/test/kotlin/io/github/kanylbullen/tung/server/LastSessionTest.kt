package io.github.kanylbullen.tung.server

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LastSessionTest {

    private suspend fun io.ktor.client.HttpClient.last(token: String, exerciseId: String, exclude: String? = null): LastSession {
        val query = exclude?.let { "?exclude_workout_id=$it" }.orEmpty()
        val response = authedGet("/api/v1/exercises/$exerciseId/last$query", token)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return ApiJson.decodeFromString(LastSession.serializer(), response.bodyAsText())
    }

    @Test
    fun theLatestFinishedSessionsCompletedSetsInOrder() = tungTest {
        val token = client.register()
        val day = 86_400_000L
        val t0 = 1_700_000_000_000

        client.put(token, id(), workout(t0, exercise(BENCH, 0, set(0, 70.0, 10), set(1, 72.5, 8))))
        val latestId = id()
        client.put(
            token, latestId,
            workout(
                t0 + day,
                exercise(SQUAT, 0, set(0, 100.0, 5)),
                exercise(BENCH, 1, set(1, 82.5, 6), set(0, 80.0, 8), set(2, 85.0, 3, completed = false)),
            ),
        )
        // In progress, newer, and therefore not "last time".
        val currentId = id()
        client.put(token, currentId, workout(t0 + 2 * day, exercise(BENCH, 0, set(0, 90.0, 1)), endedAt = null))
        // Deleted, newest of all: ignored.
        val deletedId = id()
        client.put(token, deletedId, workout(t0 + 3 * day, exercise(BENCH, 0, set(0, 999.0, 1))))
        client.deleteWorkout(token, deletedId)

        val last = client.last(token, BENCH, exclude = currentId)
        assertEquals(latestId, last.workoutId)
        assertEquals(t0 + day, last.startedAt)
        assertEquals(listOf(80.0 to 8, 82.5 to 6), last.sets.map { it.weightKg to it.reps }, "completed sets only, by position")

        val older = client.last(token, BENCH, exclude = latestId)
        assertEquals(listOf(70.0 to 10, 72.5 to 8), older.sets.map { it.weightKg to it.reps })
    }

    @Test
    fun noHistoryAndOtherUsersHistory() = tungTest {
        val alice = client.register()
        val bob = client.register()
        client.put(alice, id(), workout(1_700_000_000_000, exercise(BENCH, 0, set(0, 80.0, 8))))

        val none = client.last(bob, BENCH)
        assertNull(none.workoutId)
        assertEquals(emptyList(), none.sets)

        assertEquals(HttpStatusCode.NotFound, client.authedGet("/api/v1/exercises/${id()}/last", alice).status)
    }
}

private suspend fun io.ktor.client.HttpClient.deleteWorkout(token: String, workoutId: String) {
    val response = delete("/api/v1/workouts/$workoutId") { bearerAuth(token) }
    assertEquals(HttpStatusCode.NoContent, response.status)
}
