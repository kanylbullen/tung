package io.github.kanylbullen.tung.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncProtocolJsonTest {

    private val request = SyncRequest(
        cursor = 42,
        changes = SyncChanges(
            exercises = listOf(SyncExercise("e", "bench_press", "Bench press", "chest", "barbell", false, 90, 1)),
            workouts = listOf(SyncWorkout("w", 10, 20, "note", "r", "d", 2, null)),
            workoutExercises = listOf(SyncWorkoutExercise("we", "w", "e", 0, 3)),
            sets = listOf(SyncSet("s", "we", 0, 80.0, 8, true, 4)),
            routines = listOf(SyncRoutine("r", "Push", 30, 5)),
            routineExercises = listOf(SyncRoutineExercise("re", "r", "e", 0, 3, 8, 6)),
            programs = listOf(SyncProgram("p", "PPL", "fixed", true, 7)),
            programDays = listOf(SyncProgramDay("d", "p", "r", 0, 5, 8, deletedAt = 9)),
        ),
    )

    @Test
    fun roundTripsThroughJson() {
        val text = Json.encodeToString(SyncRequest.serializer(), request)
        assertEquals(request, Json.decodeFromString(SyncRequest.serializer(), text))
        assertEquals(8, request.changes.size())
        assertTrue(SyncChanges().isEmpty())
    }

    @Test
    fun keysAreSnakeCase() {
        val text = Json.encodeToString(SyncRequest.serializer(), request)
        val changes = Json.parseToJsonElement(text).jsonObject["changes"]!!.jsonObject
        assertEquals(
            setOf("exercises", "workouts", "workout_exercises", "sets", "routines", "routine_exercises", "programs", "program_days"),
            changes.keys,
        )
        for (key in listOf(
            "\"name_key\"", "\"muscle_group\"", "\"is_custom\"", "\"rest_seconds\"", "\"updated_at\"",
            "\"started_at\"", "\"ended_at\"", "\"routine_id\"", "\"program_day_id\"", "\"workout_id\"", "\"exercise_id\"",
            "\"workout_exercise_id\"", "\"weight_kg\"", "\"last_used_at\"", "\"target_sets\"", "\"target_reps\"",
            "\"is_active\"", "\"program_id\"", "\"deleted_at\"",
        )) {
            assertTrue("$key missing from $text", key in text)
        }
        assertTrue("no camelCase key may appear: $text", Regex("\"[a-z]+[A-Z]\\w*\":").find(text) == null)
    }

    @Test
    fun aTombstoneMayLeaveOutEverythingButIdAndTimestamps() {
        val row = Json.decodeFromString(SyncSet.serializer(), """{"id":"s","updated_at":5,"deleted_at":5}""")
        assertEquals(SyncSet("s", updatedAt = 5, deletedAt = 5), row)
        val empty = Json.decodeFromString(SyncRequest.serializer(), "{}")
        assertEquals(0L, empty.cursor)
        assertTrue(empty.changes.isEmpty())
    }
}
