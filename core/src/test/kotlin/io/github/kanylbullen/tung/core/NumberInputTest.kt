package io.github.kanylbullen.tung.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumberInputTest {

    @Test
    fun parseWeight_acceptsDecimalComma() {
        assertEquals(82.5, parseWeight("82,5") ?: -1.0, 0.0)
    }

    @Test
    fun parseWeight_acceptsDecimalPoint() {
        assertEquals(82.5, parseWeight("82.5") ?: -1.0, 0.0)
    }

    @Test
    fun parseWeight_trimsWhitespace() {
        assertEquals(100.0, parseWeight("  100 ") ?: -1.0, 0.0)
    }

    @Test
    fun parseWeight_emptyIsNull() {
        assertNull(parseWeight(""))
        assertNull(parseWeight("   "))
    }

    @Test
    fun parseWeight_negativeIsNull() {
        assertNull(parseWeight("-5"))
        assertNull(parseWeight("-2,5"))
    }

    @Test
    fun parseWeight_garbageIsNull() {
        assertNull(parseWeight("abc"))
        assertNull(parseWeight("8,2,5"))
        assertNull(parseWeight("NaN"))
    }

    @Test
    fun parseWeight_zeroIsAllowedForBodyweight() {
        assertEquals(0.0, parseWeight("0") ?: -1.0, 0.0)
    }

    @Test
    fun parseReps_wholeNumbersOnly() {
        assertEquals(8, parseReps("8"))
        assertNull(parseReps("8,5"))
        assertNull(parseReps("-1"))
        assertNull(parseReps(""))
    }

    @Test
    fun stepWeight_movesInTwoAndAHalfFromThirtyUp() {
        assertEquals(82.5, stepWeight(80.0, 1), 0.0)
        assertEquals(77.5, stepWeight(80.0, -1), 0.0)
    }

    @Test
    fun stepWeight_movesInOneKiloBelowThirty() {
        assertEquals(21.0, stepWeight(20.0, 1), 0.0)
        assertEquals(30.0, stepWeight(29.0, 1), 0.0)
        assertEquals(32.5, stepWeight(30.0, 1), 0.0)
        assertEquals(29.0, stepWeight(30.0, -1), 0.0)
        assertEquals(32.5, stepWeight(29.0, 2), 0.0)
        assertEquals(29.0, stepWeight(32.5, -2), 0.0)
    }

    @Test
    fun stepWeight_neverGoesAboveTheMaximum() {
        assertEquals(MAX_WEIGHT_KG, stepWeight(998.0, 1), 0.0)
        assertEquals(MAX_WEIGHT_KG, stepWeight(MAX_WEIGHT_KG, 1), 0.0)
    }

    @Test
    fun stepWeight_neverGoesBelowZero() {
        assertEquals(0.0, stepWeight(1.0, -1), 0.0)
    }

    @Test
    fun stepReps_neverGoesBelowZero() {
        assertEquals(0, stepReps(0, -1))
        assertEquals(9, stepReps(8, 1))
    }

    @Test
    fun formatWeight_dropsTrailingZeros() {
        assertEquals("80", formatWeight(80.0))
        assertEquals("82,5", formatWeight(82.5))
        assertEquals("1,25", formatWeight(1.25))
        assertEquals("82.5", formatWeight(82.5, '.'))
    }
}
