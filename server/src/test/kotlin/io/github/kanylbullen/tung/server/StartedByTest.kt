package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.core.StartedBy
import io.github.kanylbullen.tung.core.sync.SyncChanges
import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `started_by` on workouts (issue #34): synced, set once, stamped `agent` by every server command. */
class StartedByTest {

    private fun workoutRow(id: String, updatedAt: Long, startedBy: String?) =
        SyncWorkout(id, 1_700_000_000_000, null, null, null, null, updatedAt, startedBy = startedBy)

    @Test
    fun syncKeepsStartedByAndANullDoesNotEraseIt() = tungTest {
        val token = client.register()
        val workoutId = id()
        val first = client.synced(token, changes = SyncChanges(workouts = listOf(workoutRow(workoutId, 1_000, StartedBy.WATCH))))
        assertEquals(StartedBy.WATCH, first.changes.workouts.single().startedBy)

        // A newer row from a client that does not know the field (null) wins the row, but keeps who started it.
        val newer = client.synced(token, changes = SyncChanges(workouts = listOf(workoutRow(workoutId, 2_000, null).copy(note = "later"))))
        val row = newer.changes.workouts.single()
        assertEquals("later", row.note)
        assertEquals(2_000, row.updatedAt)
        assertEquals(StartedBy.WATCH, row.startedBy)

        // Another device pulling from scratch sees it, and so does the REST API.
        assertEquals(StartedBy.WATCH, client.synced(token).changes.workouts.single { it.id == workoutId }.startedBy)
        assertEquals(StartedBy.WATCH, client.authedGet("/api/v1/workouts/$workoutId", token).workout().startedBy)
    }

    @Test
    fun anUnknownStartedByIsRejected() = tungTest {
        val token = client.register()
        val bad = client.sync(token, changes = SyncChanges(workouts = listOf(workoutRow(id(), 1_000, "robot"))))
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("invalid_rows", bad.errorCode())
        assertTrue("started_by" in bad.bodyAsText(), bad.bodyAsText())

        val put = client.put(token, id(), workout(1_700_000_000_000).copy(startedBy = "robot"))
        assertEquals(HttpStatusCode.BadRequest, put.status, put.bodyAsText())
    }

    @Test
    fun aServerCommandStartsAWorkoutAsTheAgent() = tungTest {
        val token = client.register()
        val workoutId = client.done(token, "start_workout")["workoutId"]!!.str
        val synced = client.synced(token).changes.workouts.single { it.id == workoutId }
        assertEquals(StartedBy.AGENT, synced.startedBy)
        assertEquals(StartedBy.AGENT, client.authedGet("/api/v1/workouts/$workoutId", token).workout().startedBy)
    }

    @Test
    fun putSetsStartedByOnceAndABodyWithoutItKeepsIt() = tungTest {
        val token = client.register()
        val workoutId = id()
        val created = client.put(token, workoutId, workout(1_700_000_000_000, updatedAt = 1_000).copy(startedBy = StartedBy.PHONE))
        assertEquals(HttpStatusCode.OK, created.status, created.bodyAsText())
        assertEquals(StartedBy.PHONE, created.workout().startedBy)

        val edited = client.put(token, workoutId, workout(1_700_000_000_000, updatedAt = 2_000, note = "edited"))
        assertEquals(HttpStatusCode.OK, edited.status, edited.bodyAsText())
        assertEquals("edited", edited.workout().note)
        assertEquals(StartedBy.PHONE, edited.workout().startedBy)
    }
}
