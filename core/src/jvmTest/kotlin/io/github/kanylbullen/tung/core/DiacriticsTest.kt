package io.github.kanylbullen.tung.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

/**
 * The generated tables (`core/tools/GenDiacritics.java`) and the Hangul
 * formula against the running JDK's Normalizer, over the whole Basic
 * Multilingual Plane. A mismatch means the JDK's Unicode is newer than the
 * tables: regenerate them.
 */
class DiacriticsTest {

    private fun jdkFold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

    @Test
    fun foldsEveryCharacterOfTheBasicMultilingualPlaneLikeTheJdk() {
        val mismatches = (0..0xFFFF).filterNot { Character.isSurrogate(it.toChar()) }.map { it.toChar().toString() }
            .filter { foldDiacritics(it) != jdkFold(it) }
            .map { "U+%04X".format(it[0].code) }
        assertEquals(emptyList<String>(), mismatches)
    }

    /** A Hangul syllable folds to its jamo, as NFD writes it, so a syllable typed so far matches the name it begins. */
    @Test
    fun foldsHangulSyllablesIntoTheirJamo() {
        assertEquals("\u1107\u1166\u11AB", foldDiacritics("벤"))
        assertEquals("\u1107\u1166", foldDiacritics("베"))
        assertEquals("\u1100\u1161", foldDiacritics("\uAC00"))
        assertEquals("\u1112\u1175\u11C2", foldDiacritics("\uD7A3"))
        assertEquals(jdkFold("벤치프레스"), foldDiacritics("벤치프레스"))
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
    fun composesALetterAndAMarkLikeTheJdk() {
        val bases = ('A'..'Z') + ('a'..'z') + (0x0391..0x03A9).map { it.toChar() } + (0x03B1..0x03C9).map { it.toChar() } +
            (0x0410..0x044F).map { it.toChar() }
        val marks = (0x0300..0x036F).map { it.toChar() }
        val mismatches = bases.flatMap { b -> marks.map { m -> "$b$m" } }
            .filter { composeDiacritics(it) != Normalizer.normalize(it, Normalizer.Form.NFC) }
            .map { "U+%04X U+%04X".format(it[0].code, it[1].code) }
        assertEquals(emptyList<String>(), mismatches)
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
