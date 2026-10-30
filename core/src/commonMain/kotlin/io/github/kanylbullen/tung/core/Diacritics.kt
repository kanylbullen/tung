package io.github.kanylbullen.tung.core

/**
 * Diacritic folding and composition without `java.text.Normalizer`, which
 * Kotlin Multiplatform does not have, from the tables `GenDiacritics.java`
 * writes out of the JDK's Unicode data (`DiacriticTables.kt`), plus the
 * Hangul syllables, whose decomposition is a formula rather than a table
 * ([appendHangulJamo]). Together they fold every character of the Basic
 * Multilingual Plane as NFD minus the marks would, which `DiacriticsTest`
 * checks against the JDK over the whole plane. Outside the plane nothing
 * folds: a surrogate pair is kept as it is (the few canonical decompositions
 * there, some Brahmic vowel signs, the musical symbols and the CJK
 * Compatibility Ideographs Supplement, do not occur in exercise names).
 */

private val folds: Map<Char, String> by lazy {
    DIACRITIC_FOLDS.split(ENTRY_SEPARATOR).filter { it.isNotEmpty() }.associate { it[0] to it.substring(1) }
}

private val compositions: Map<Pair<Char, Char>, Char> by lazy {
    DIACRITIC_COMPOSITIONS.split(ENTRY_SEPARATOR).filter { it.isNotEmpty() }.associate { (it[0] to it[1]) to it[2] }
}

/** The few marks that are themselves a sequence of other marks (U+0344 is a diaeresis and an acute). */
private val markDecompositions: Map<Char, String> by lazy {
    DIACRITIC_MARK_DECOMPOSITIONS.split(ENTRY_SEPARATOR).filter { it.isNotEmpty() }.associate { it[0] to it.substring(1) }
}

private const val ENTRY_SEPARATOR = '\u0001'

/**
 * [text] canonically decomposed with every non-spacing mark removed, which is
 * NFD minus `\p{Mn}`: "Bänkpress" → "Bankpress", the ohm sign → omega, a lone
 * combining mark → nothing, a Hangul syllable → its jamo (so "벤치프레스"
 * contains "베", as the picker's search needs). Case is kept.
 */
fun foldDiacritics(text: String): String {
    val out = StringBuilder(text.length)
    for (c in text) {
        val folded = folds[c]
        when {
            folded != null -> out.append(folded)
            c in HANGUL_SYLLABLES -> out.appendHangulJamo(c)
            c.category != CharCategory.NON_SPACING_MARK -> out.append(c)
        }
    }
    return out.toString()
}

/** The precomposed Hangul syllables, U+AC00–U+D7A3, every one a leading consonant, a vowel and an optional trailing consonant. */
private val HANGUL_SYLLABLES = '\uAC00'..'\uD7A3'
private const val HANGUL_LEADING_BASE = 0x1100
private const val HANGUL_VOWEL_BASE = 0x1161
private const val HANGUL_TRAILING_BASE = 0x11A7
private const val HANGUL_VOWEL_COUNT = 21
private const val HANGUL_TRAILING_COUNT = 28

/**
 * [syllable]'s conjoining jamo, as NFD writes them (the Unicode Standard,
 * "Hangul Syllable Decomposition"): the leading consonant, the vowel, and
 * the trailing consonant when the syllable has one. Jamo are letters, not
 * marks, so all of them are kept.
 */
private fun StringBuilder.appendHangulJamo(syllable: Char) {
    val index = syllable.code - HANGUL_SYLLABLES.first.code
    val perLeading = HANGUL_VOWEL_COUNT * HANGUL_TRAILING_COUNT
    append((HANGUL_LEADING_BASE + index / perLeading).toChar())
    append((HANGUL_VOWEL_BASE + index % perLeading / HANGUL_TRAILING_COUNT).toChar())
    val trailing = index % HANGUL_TRAILING_COUNT
    if (trailing > 0) append((HANGUL_TRAILING_BASE + trailing).toChar())
}

/**
 * Each base letter followed by a non-spacing mark composed into the
 * precomposed character when one exists, stepwise and in the order written:
 * "a" + U+030A → "å", "å" + U+0301 → "ǻ". That is NFC for text as keyboards
 * and speech recognisers produce it; marks that compose with nothing, and
 * marks written in an order NFC would reorder, stay as they are.
 */
fun composeDiacritics(text: String): String {
    if (text.none { it.category == CharCategory.NON_SPACING_MARK }) return text
    val out = StringBuilder(text.length)
    for (c in text) {
        if (c.category == CharCategory.NON_SPACING_MARK) {
            for (mark in markDecompositions[c] ?: c.toString()) out.appendMark(mark)
        } else {
            out.append(c)
        }
    }
    return out.toString()
}

private fun StringBuilder.appendMark(mark: Char) {
    if (isNotEmpty()) {
        val composed = compositions[this[length - 1] to mark]
        if (composed != null) {
            this[length - 1] = composed
            return
        }
    }
    append(mark)
}
