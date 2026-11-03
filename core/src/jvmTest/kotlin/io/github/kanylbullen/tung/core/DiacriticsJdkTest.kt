package io.github.kanylbullen.tung.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Normalizer

/**
 * The generated tables (`core/tools/GenDiacritics.java`) and the Hangul
 * formula against the running JDK's Normalizer, over the whole Basic
 * Multilingual Plane. A mismatch means the JDK's Unicode is newer than the
 * tables: regenerate them.
 */
class DiacriticsJdkTest {

    private fun jdkFold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

    @Test
    fun foldsAHangulWordLikeTheJdk() {
        assertEquals(jdkFold("벤치프레스"), foldDiacritics("벤치프레스"))
    }

    @Test
    fun foldsEveryCharacterOfTheBasicMultilingualPlaneLikeTheJdk() {
        val mismatches = (0..0xFFFF).filterNot { Character.isSurrogate(it.toChar()) }.map { it.toChar().toString() }
            .filter { foldDiacritics(it) != jdkFold(it) }
            .map { "U+%04X".format(it[0].code) }
        assertEquals(emptyList<String>(), mismatches)
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
}
