package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.sync.SyncChanges
import io.github.kanylbullen.tung.core.sync.SyncProgram
import io.github.kanylbullen.tung.core.sync.SyncRoutine
import io.github.kanylbullen.tung.core.sync.SyncSet
import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.github.kanylbullen.tung.core.sync.SyncWorkoutExercise
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A deleted row keeps nothing of what it held (issue #148, V17): the stored
 * tombstone, which every sync sends, has its ids and times and no content,
 * whichever path deleted it.
 */
class TombstoneContentTest {

    private val bench = catalogExerciseId("bench_press")

    private fun workout(id: String, updatedAt: Long) = SyncWorkout(
        id, 1_700_000_000_000, 1_700_003_600_000, "felt heavy", id(), id(), updatedAt,
        startedBy = "phone", avgHeartRate = 128, maxHeartRate = 171, activeCalories = 342, healthConnectId = "hc-abc",
        importSource = "jefit", importBatchId = id(),
    )

    /** A workout with one exercise and one set, all with values. */
    private fun training(workoutId: String, exerciseRowId: String, setId: String) = SyncChanges(
        workouts = listOf(workout(workoutId, 1_000)),
        workoutExercises = listOf(SyncWorkoutExercise(exerciseRowId, workoutId, bench, 0, 1_000)),
        sets = listOf(SyncSet(setId, exerciseRowId, 0, 82.5, 8, true, 1_000)),
    )

    private fun assertEmptied(w: SyncWorkout) {
        assertEquals(0, w.startedAt)
        for ((name, value) in listOf(
            "ended_at" to w.endedAt, "note" to w.note, "routine_id" to w.routineId, "program_day_id" to w.programDayId,
            "started_by" to w.startedBy, "avg_heart_rate" to w.avgHeartRate, "max_heart_rate" to w.maxHeartRate,
            "active_calories" to w.activeCalories, "health_connect_id" to w.healthConnectId,
            "import_source" to w.importSource, "import_batch_id" to w.importBatchId,
        )) assertNull(value, name)
    }

    private fun assertEmptied(s: SyncSet) {
        assertEquals(0.0, s.weightKg)
        assertEquals(0, s.reps)
        assertEquals(false, s.completed)
    }

    @Test
    fun aTombstoneFromSyncClearsTheRow() = tungTest {
        val token = client.register()
        val (workoutId, exerciseRowId, setId) = Triple(id(), id(), id())
        client.synced(token, changes = training(workoutId, exerciseRowId, setId))

        val answer = client.synced(token, changes = SyncChanges(
            workouts = listOf(SyncWorkout(workoutId, updatedAt = 2_000, deletedAt = 2_000)),
            workoutExercises = listOf(SyncWorkoutExercise(exerciseRowId, updatedAt = 2_000, deletedAt = 2_000)),
            sets = listOf(SyncSet(setId, updatedAt = 2_000, deletedAt = 2_000)),
        ))
        // The stored rows come back as they now are, and so does a pull from scratch.
        for (changes in listOf(answer.changes, client.synced(token).changes)) {
            val w = changes.workouts.single { it.id == workoutId }
            assertEquals(2_000, w.deletedAt)
            assertEmptied(w)
            val s = changes.sets.single { it.id == setId }
            assertEquals(2_000, s.deletedAt)
            assertEquals(exerciseRowId, s.workoutExerciseId)
            assertEmptied(s)
            assertEquals(workoutId, changes.workoutExercises.single { it.id == exerciseRowId }.workoutId)
        }
    }

    @Test
    fun aDeleteThroughTheApiClearsTheRowAndItsChildren() = tungTest {
        val token = client.register()
        val (workoutId, exerciseRowId, setId) = Triple(id(), id(), id())
        client.synced(token, changes = training(workoutId, exerciseRowId, setId))

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/workouts/$workoutId") { bearerAuth(token) }.status)

        val changes = client.synced(token).changes
        assertEmptied(changes.workouts.single { it.id == workoutId })
        assertEmptied(changes.sets.single { it.id == setId })
    }

    @Test
    fun routinesAndProgramsLoseTheirNames() = tungTest {
        val token = client.register()
        val (routineId, programId) = id() to id()
        client.synced(token, changes = SyncChanges(
            routines = listOf(SyncRoutine(routineId, "Push day", 5_000, 1_000)),
            programs = listOf(SyncProgram(programId, "My split", "rotating", true, 1_000)),
        ))
        val changes = client.synced(token, changes = SyncChanges(
            routines = listOf(SyncRoutine(routineId, updatedAt = 2_000, deletedAt = 2_000)),
            programs = listOf(SyncProgram(programId, updatedAt = 2_000, deletedAt = 2_000)),
        )).changes
        changes.routines.single().let {
            assertEquals("", it.name)
            assertNull(it.lastUsedAt)
        }
        changes.programs.single().let {
            assertEquals("", it.name)
            assertEquals(false, it.isActive)
        }
    }

    @Test
    fun aNewerLiveRowBringsItsOwnValuesBack() = tungTest {
        val token = client.register()
        val (workoutId, exerciseRowId, setId) = Triple(id(), id(), id())
        client.synced(token, changes = training(workoutId, exerciseRowId, setId))
        client.synced(token, changes = SyncChanges(workouts = listOf(SyncWorkout(workoutId, updatedAt = 2_000, deletedAt = 2_000))))

        val back = client.synced(token, changes = SyncChanges(workouts = listOf(workout(workoutId, 3_000).copy(note = "restored"))))
            .changes.workouts.single()
        assertNull(back.deletedAt)
        assertEquals("restored", back.note)
        assertEquals(1_700_000_000_000, back.startedAt)
        assertEquals(128, back.avgHeartRate)
    }
}
