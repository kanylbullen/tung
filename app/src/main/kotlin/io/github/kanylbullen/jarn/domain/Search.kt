package io.github.kanylbullen.jarn.domain

import java.text.Normalizer
import java.util.Locale

private val combiningMarks = Regex("\\p{Mn}+")

/** Lowercase and strip diacritics, so "Bänkpress" becomes "bankpress". */
fun normalizeForSearch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(combiningMarks, "")
        .lowercase(Locale.ROOT)
        .trim()

/** Diacritic- and case-insensitive substring match. A blank query matches everything. */
fun matchesSearch(name: String, query: String): Boolean {
    val q = normalizeForSearch(query)
    return q.isEmpty() || normalizeForSearch(name).contains(q)
}
