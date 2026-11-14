package io.github.kanylbullen.tung.wear

import io.github.kanylbullen.tung.core.wear.WatchWorkoutState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneDefaultRestTest {

    private fun state(rest: Int?) = WatchWorkoutState(updatedAt = 0L, defaultRestSeconds = rest)

    /** Each new value is stored once; a state without one (an older phone, or nothing published) stores nothing. */
    @Test
    fun storesEachNewValueAndIgnoresMissingOnes() = runBlocking {
        val stored = mutableListOf<Int>()
        followPhoneDefaultRest(
            flowOf(null, state(null), state(120), state(120), state(null), state(60)),
        ) { stored += it }
        assertEquals(listOf(120, 60), stored)
    }
}
