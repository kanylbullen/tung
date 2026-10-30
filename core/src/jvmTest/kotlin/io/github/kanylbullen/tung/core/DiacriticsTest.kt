package io.github.kanylbullen.tung.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Normalizer

/**
 * The generated tables (`core/tools/GenDiacritics.java`) against the running
 * JDK's Normalizer. A mismatch means the JDK's Unicode is newer than the
 * tables: regenerate them.
 */
class DiacriticsTest {

    private fun jdkFold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

    /** The generator leaves out Hangul syllables and the CJK compatibility ideographs. */
    private fun inScope(code: Int): Boolean =
        !Character.isSurrogate(code.toChar()) && code !in 0xAC00..0xD7A3 && code !in 0xF900..0xFAFF

    @Test
    fun foldsEveryCharacterOfTheBasicMultilingualPlaneLikeTheJdk() {
        val mismatches = (0..0xFFFF).filter(::inScope).map { it.toChar().toString() }
            .filter { foldDiacritics(it) != jdkFold(it) }
            .map { "U+%04X".format(it[0].code) }
        assertEquals(emptyList<String>(), mismatches)
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
