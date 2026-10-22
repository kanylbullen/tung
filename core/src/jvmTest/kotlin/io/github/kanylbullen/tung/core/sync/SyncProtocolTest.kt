package io.github.kanylbullen.tung.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncProtocolTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun keysAreSnakeCaseOnTheWire() {
        val request = SyncRequest(
            cursor = 5,
            changes = SyncChanges(
                workoutExercises = listOf(SyncWorkoutExercise("we", "w", "e", 0, 10)),
                routineExercises = listOf(SyncRoutineExercise("re", "r", "e", 1, 3, null, 10)),
                programDays = listOf(SyncProgramDay("d", "p", "r", 0, 5, 10, 11)),
            ),
        )
        val text = json.encodeToString(SyncRequest.serializer(), request)
        val changes = Json.parseToJsonElement(text).jsonObject.getValue("changes").jsonObject
        assertEquals(
            setOf("exercises", "workouts", "workout_exercises", "sets", "routines", "routine_exercises", "programs", "program_days"),
            changes.keys,
        )
        assertTrue(text.contains("\"workout_id\":\"w\""))
        assertTrue(text.contains("\"target_reps\":null"))
        assertTrue(text.contains("\"deleted_at\":11"))
        assertEquals(request, json.decodeFromString(SyncRequest.serializer(), text))
    }

    @Test
    fun sizeCountsEveryList() {
        val changes = SyncChanges(
            exercises = listOf(SyncExercise("e", null, "X", "chest", "barbell", true, null, 1)),
            sets = listOf(SyncSet("s", "we", 0, 1.0, 1, true, 1), SyncSet("t", "we", 1, 1.0, 1, true, 1)),
        )
        assertEquals(3, changes.size())
        assertTrue(SyncChanges().isEmpty())
    }
}
