package io.github.kanylbullen.tung.core

/**
 * Diacritic folding and composition without `java.text.Normalizer`, which
 * Kotlin Multiplatform does not have, from the tables `GenDiacritics.java`
 * writes out of the JDK's Unicode data (`DiacriticTables.kt`). They cover
 * the Basic Multilingual Plane except Hangul syllables and the CJK
 * compatibility ideographs, which are not diacritics; `DiacriticsTest`
 * compares both functions with the JDK over that range.
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
 * combining mark → nothing. Case is kept.
 */
fun foldDiacritics(text: String): String {
    val out = StringBuilder(text.length)
    for (c in text) {
        val folded = folds[c]
        when {
            folded != null -> out.append(folded)
            c.category != CharCategory.NON_SPACING_MARK -> out.append(c)
        }
    }
    return out.toString()
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
