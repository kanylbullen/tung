package io.github.kanylbullen.tung.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {

    @Test
    fun withoutDiacriticsMatchesName() {
        assertTrue(matchesSearch("Bänkpress", "bank"))
    }

    @Test
    fun withDiacriticsMatchesToo() {
        assertTrue(matchesSearch("Bänkpress", "BÄNK"))
    }

    @Test
    fun matchesInsideTheName() {
        assertTrue(matchesSearch("Lutande bänkpress", "bankp"))
        assertTrue(matchesSearch("Höftlyft", "hoft"))
        assertTrue(matchesSearch("Rumänska marklyft", "rumanska"))
    }

    @Test
    fun blankQueryMatchesEverything() {
        assertTrue(matchesSearch("Knäböj", "  "))
    }

    @Test
    fun nonMatchingQuery() {
        assertFalse(matchesSearch("Knäböj", "press"))
    }

    @Test
    fun normalizeStripsMarksAndCase() {
        assertEquals("aao", normalizeForSearch("ÅÄÖ"))
    }
}
