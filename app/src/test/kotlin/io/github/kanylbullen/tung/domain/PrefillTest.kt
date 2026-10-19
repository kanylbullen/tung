package io.github.kanylbullen.tung.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrefillTest {

    private val lastSession = listOf(LoggedSet(80.0, 8), LoggedSet(85.0, 6), LoggedSet(90.0, 4))

    @Test
    fun previousSetInThisWorkoutWinsOverLastSession() {
        val result = prefillSet(LoggedSet(82.5, 7), lastSession, position = 1)
        assertEquals(LoggedSet(82.5, 7), result)
    }

    @Test
    fun firstSetFallsBackToLastSessionAtSamePosition() {
        assertEquals(LoggedSet(80.0, 8), prefillSet(null, lastSession, position = 0))
    }

    @Test
    fun usesSamePositionNotFirstSet() {
        assertEquals(LoggedSet(90.0, 4), prefillSet(null, lastSession, position = 2))
    }

    @Test
    fun beyondLastSessionWithNothingInThisWorkoutIsNull() {
        assertNull(prefillSet(null, lastSession, position = 3))
    }

    @Test
    fun nothingToGoOnIsNull() {
        assertNull(prefillSet(null, emptyList(), position = 0))
    }
}
