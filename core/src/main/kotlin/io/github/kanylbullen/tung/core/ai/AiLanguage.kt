package io.github.kanylbullen.tung.core.ai

import java.util.Locale

/**
 * The language the model writes its text in, as a plain English name, from
 * [tag] (BCP 47): only the language subtag counts, never the region or the
 * extensions. A phone in `en-SE-u-fw-mon-…` shows the app in English, and a
 * model handed that whole tag read the region as Swedish (issue #75).
 *
 * Only the languages the app ships resources for have a name; anything else
 * is English, as the app itself falls back to its English resources.
 */
fun aiLanguageName(tag: String): String =
    when (tag.trim().lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')) {
        "sv" -> "Swedish"
        else -> "English"
    }
