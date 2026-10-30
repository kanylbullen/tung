package io.github.kanylbullen.tung.core.catalog

/**
 * `Intl.Collator`, the browser's collation. Only what [browseNameOrder] needs
 * is declared.
 */
@JsName("Intl")
private external object Intl {
    class Collator(locales: String, options: dynamic) {
        fun compare(a: String, b: String): Int
    }
}

/**
 * Names in the order of [locale] (a BCP 47 tag, e.g. the browser's
 * `navigator.language`), ignoring case but not accents, as the picker sorts:
 * the browser's `Intl.Collator` at sensitivity `accent`, the JVM's
 * `Collator.SECONDARY`. Web only: [browse] itself takes any
 * `Comparator<String>`, and another platform hands it its own collation.
 */
fun browseNameOrder(locale: String): Comparator<String> {
    val options: dynamic = js("({})")
    options.sensitivity = "accent"
    val collator = Intl.Collator(locale, options)
    return Comparator { a, b -> collator.compare(a, b) }
}
