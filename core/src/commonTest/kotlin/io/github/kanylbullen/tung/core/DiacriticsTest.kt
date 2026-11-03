package io.github.kanylbullen.tung.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Folding and composing on every target, with the values written out; the
 * sweeps against the JDK's Normalizer over the whole Basic Multilingual Plane
 * are `DiacriticsJdkTest` in jvmTest.
 */
class DiacriticsTest {

    /** A Hangul syllable folds to its jamo, as NFD writes it, so a syllable typed so far matches the name it begins. */
    @Test
    fun foldsHangulSyllablesIntoTheirJamo() {
        assertEquals("\u1107\u1166\u11AB", foldDiacritics("벤"))
        assertEquals("\u1107\u1166", foldDiacritics("베"))
        assertEquals("\u1100\u1161", foldDiacritics("\uAC00"))
        assertEquals("\u1112\u1175\u11C2", foldDiacritics("\uD7A3"))
        assertTrue(matchesSearch("벤치프레스", "베"))
        assertTrue(matchesSearch("벤치프레스", "벤치"))
        assertFalse(matchesSearch("벤치프레스", "벤트"))
    }

    /** A CJK compatibility ideograph is its unified ideograph, as before; outside the plane nothing folds. */
    @Test
    fun foldsCompatibilityIdeographsAndKeepsSupplementaryCharacters() {
        assertEquals("\u8C48", foldDiacritics("\uF900"))
        assertEquals("\u9E9F", foldDiacritics("\uF9F3"))
        // A pair of surrogates stays itself, whatever its decomposition would be.
        assertEquals("\uD834\uDD5E", foldDiacritics("\uD834\uDD5E"))
        assertEquals("\uD87E\uDC00", foldDiacritics("\uD87E\uDC00"))
    }

    @Test
    fun foldsWords() {
        assertEquals("Bankpress", foldDiacritics("Bänkpress"))
        assertEquals("Angstrom", foldDiacritics("Ångström"))
        assertEquals("creme brulee", foldDiacritics("crème brûlée"))
        assertEquals("a", foldDiacritics("ä"))
        assertEquals("Lat pulldown", foldDiacritics("Lat pulldown"))
    }

    @Test
    fun composesWordsAndLeavesComposedTextAlone() {
        assertEquals("bänkpress", composeDiacritics("bänkpress"))
        assertEquals("ǻ", composeDiacritics("ǻ"))
        assertEquals("bänkpress", composeDiacritics("bänkpress"))
        // No precomposed "x with acute" exists, so the mark stays, as in NFC.
        assertEquals("x́", composeDiacritics("x́"))
    }
}
