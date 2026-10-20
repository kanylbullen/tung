package io.github.kanylbullen.tung.core.wear

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.RestTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProtocolTest {

    private val state = WatchWorkoutState(
        workoutId = "w1",
        startedAt = 1_000L,
        exercises = listOf(
            WatchExercise(
                workoutExerciseId = "we1",
                name = "Bänkpress",
                sets = listOf(
                    WatchSet("s1", 0, 80.0, 8, completed = true),
                    WatchSet("s2", 1, 82.5, 6, completed = false),
                ),
                lastTime = listOf(WatchLastSet(80.0, 8), WatchLastSet(80.0, 7)),
            ),
            WatchExercise("we2", "Back squat", emptyList()),
        ),
        currentSetId = "s2",
        rest = WatchRest(endsAtMillis = 91_000L, durationMillis = 90_000L),
        routines = listOf(WatchRoutine("r1", "Push", 3)),
        updatedAt = 5_000L,
    )

    @Test
    fun stateRoundTrips() {
        val text = WatchProtocol.encodeState(state)
        assertEquals(state, WatchProtocol.decodeState(text))
    }

    @Test
    fun noWorkoutRoundTrips() {
        val idle = WatchWorkoutState(routines = listOf(WatchRoutine("r1", "Pull", 4)), updatedAt = 7L)
        val decoded = WatchProtocol.decodeState(WatchProtocol.encodeState(idle))
        assertEquals(idle, decoded)
        assertFalse(decoded.isActive)
        assertNull(decoded.rest)
    }

    @Test
    fun unknownFieldsFromANewerPhoneAreIgnored() {
        val text = """{"workoutId":null,"updatedAt":3,"somethingNew":[1,2],"routines":[]}"""
        assertEquals(WatchWorkoutState(updatedAt = 3L), WatchProtocol.decodeState(text))
    }

    @Test
    fun everyCommandRoundTrips() {
        val commands: List<WatchCommand> = listOf(
            WatchCommand.LogSet("s1", "c1", 10L),
            WatchCommand.SetWeight("s1", 82.5, "c2", 11L),
            WatchCommand.SetReps("s1", 8, "c3", 12L),
            WatchCommand.SelectSet("s2", "c4", 13L),
            WatchCommand.StartWorkout("c5", 14L),
            WatchCommand.StartRoutine("r1", "c6", 15L),
            WatchCommand.FinishWorkout("c7", 16L),
            WatchCommand.SkipRest("c8", 17L),
            WatchCommand.AddRest30("c9", 18L),
        )
        // One of each subtype, so a new command cannot be added without a round trip here.
        assertEquals(9, commands.map { it::class }.toSet().size)
        commands.forEach { command ->
            assertEquals(command, WatchProtocol.decodeCommand(WatchProtocol.encodeCommand(command)))
        }
    }

    @Test
    fun commandsCarryATypeTag() {
        val text = WatchProtocol.encodeCommand(WatchCommand.AddRest30("c9", 18L))
        assertTrue(text, text.contains("\"type\":\"add_rest_30\""))
        assertEquals(
            WatchCommand.LogSet("s1", "c1", 10L),
            WatchProtocol.decodeCommand("""{"type":"log_set","setId":"s1","commandId":"c1","sentAt":10}"""),
        )
    }

    @Test
    fun lookupsAndConversions() {
        assertEquals("we1", state.exerciseOf("s2")?.workoutExerciseId)
        assertEquals(82.5, state.set("s2")?.weightKg ?: 0.0, 0.0)
        assertNull(state.set("nope"))
        assertEquals(RestTimer(91_000L, 90_000L), state.rest?.toRestTimer())
        assertEquals(WatchRest(5L, 3L), RestTimer(5L, 3L).toWatch())
        assertEquals(LoggedSet(80.0, 8), LoggedSet(80.0, 8).toWatch().toLogged())
    }
}
