package io.github.kanylbullen.tung.voice

/** One way to ask the recogniser: a language tag, and whether to prefer its on-device model. */
internal data class RecognizerAttempt(val tag: String, val preferOffline: Boolean)

/**
 * What the recogniser says it has (`SpeechRecognizer.checkRecognitionSupport`, Android 13+):
 * the languages it can do on the device right now, and those it can do online.
 */
internal data class RecognizerLanguages(val installed: List<String>, val online: List<String>)

/**
 * The tags to try, in order, for [appLanguage] (the language the app shows, "en" or "sv") on a phone
 * whose locale is [deviceTag]. The phone's region is kept when its language is the app's
 * ("sv-SE" on a Swedish phone), never its extensions or a region of another language: a Pixel in
 * English with a Swedish region is `en-SE-u-fw-mon-…`, and Google's recogniser has no pack for
 * `en-SE` (error 12). Without a region of its own the language gets its usual one
 * ([USUAL_REGION]: `sv-SE`, `en-US`), since some recognisers refuse a bare language. With [support] the first attempt is a variant installed on the phone (its
 * own `en-US`), then one it does online; without it (before Android 13, or no answer) the phone's
 * variant offline, then online, then the bare language. A later attempt runs only after a
 * language error ([isLanguageError]).
 */
internal fun recognizerAttempts(appLanguage: String, deviceTag: String, support: RecognizerLanguages?): List<RecognizerAttempt> {
    val language = languageOf(appLanguage)
    val deviceParts = deviceTag.replace('_', '-').split('-')
    val region = deviceParts.getOrNull(1)
        ?.takeIf { languageOf(deviceTag) == language && it.length == 2 && it.all(Char::isLetter) }
        ?.uppercase()
    val preferred = when {
        region != null -> "$language-$region"
        else -> USUAL_REGION[language]?.let { "$language-$it" } ?: language
    }
    if (support != null) {
        fun pick(tags: List<String>): String? =
            tags.firstOrNull { it.equals(preferred, ignoreCase = true) } ?: tags.firstOrNull { languageOf(it) == language }
        val attempts = listOfNotNull(
            pick(support.installed)?.let { RecognizerAttempt(it, preferOffline = true) },
            pick(support.online)?.let { RecognizerAttempt(it, preferOffline = false) },
        )
        if (attempts.isNotEmpty()) return attempts
    }
    return listOf(
        RecognizerAttempt(preferred, preferOffline = true),
        RecognizerAttempt(preferred, preferOffline = false),
        RecognizerAttempt(language, preferOffline = false),
    ).distinct()
}

/** The region each language the app ships is most often recognised in. */
private val USUAL_REGION = mapOf("en" to "US", "sv" to "SE")

/** `SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED` (12) and `ERROR_LANGUAGE_UNAVAILABLE` (13), API 31: the values, so minSdk 26 compiles. */
internal fun isLanguageError(error: Int): Boolean = error == 12 || error == 13

private fun languageOf(tag: String): String = tag.replace('_', '-').substringBefore('-').lowercase()
