package io.github.kanylbullen.tung.core

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

/**
 * Keeps the items whose displayed name matches [query]. The caller pairs each
 * item with the name the user actually sees (localised for catalogue
 * exercises), so the search follows the screen rather than the database.
 */
fun <T> filterByDisplayName(items: List<Pair<T, String>>, query: String): List<Pair<T, String>> {
    val q = normalizeForSearch(query)
    if (q.isEmpty()) return items
    return items.filter { (_, name) -> normalizeForSearch(name).contains(q) }
}
