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

    // Displayed names as ExercisesViewModel pairs them: catalogue ones localised, custom ones as typed.
    private data class Item(val id: Long)

    private val english = listOf(
        Item(1) to "Bench press",
        Item(2) to "Incline bench press",
        Item(3) to "Back squat",
        Item(4) to "Bänkpress med paus", // custom exercise, keeps the name the user typed
    )

    private val swedish = listOf(
        Item(1) to "Bänkpress",
        Item(2) to "Lutande bänkpress",
        Item(3) to "Knäböj",
        Item(4) to "Bänkpress med paus",
    )

    @Test
    fun searchesDisplayedNamesInEnglish() {
        val hits = filterByDisplayName(english, "bench").map { it.first.id }
        assertEquals(listOf(1L, 2L), hits)
        assertTrue(filterByDisplayName(english, "knäböj").isEmpty())
    }

    @Test
    fun searchesDisplayedNamesInSwedishIgnoringDiacritics() {
        val hits = filterByDisplayName(swedish, "bank").map { it.first.id }
        assertEquals(listOf(1L, 2L, 4L), hits)
        assertEquals(listOf(3L), filterByDisplayName(swedish, "KNABOJ").map { it.first.id })
        assertTrue(filterByDisplayName(swedish, "bench").isEmpty())
    }

    @Test
    fun blankQueryKeepsEveryDisplayedName() {
        assertEquals(english, filterByDisplayName(english, " "))
    }
}
