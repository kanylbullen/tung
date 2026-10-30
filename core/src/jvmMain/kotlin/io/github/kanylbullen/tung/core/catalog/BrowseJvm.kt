package io.github.kanylbullen.tung.core.catalog

import java.text.Collator
import java.util.Locale

/**
 * Names in the order of [locale], ignoring case but not accents, as the picker
 * sorts. JVM only: [browse] itself takes any `Comparator<String>`, and another
 * platform hands it its own collation.
 */
fun browseNameOrder(locale: Locale): Comparator<String> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    return Comparator { a, b -> collator.compare(a, b) }
}
