package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.web.ui.niceTicks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChartsTest {
    @Test
    fun niceTicksCoverTheRangeInRoundSteps() {
        assertEquals(listOf(70.0, 75.0, 80.0), niceTicks(70.0, 80.0))
        val big = niceTicks(1680.0, 1920.0)
        assertTrue(big.first() <= 1680.0 && big.last() >= 1920.0, "$big")
        assertTrue(big.size <= 5, "$big")
        assertEquals(listOf(5.0), niceTicks(5.0, 5.0), "one value, one tick")
    }
}
