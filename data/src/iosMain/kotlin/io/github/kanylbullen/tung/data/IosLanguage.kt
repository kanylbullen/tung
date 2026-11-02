package io.github.kanylbullen.tung.data

import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

/**
 * The language the iPhone app speaks, as a tag the shared code knows: `sv`
 * when the first of the user's preferred languages is Swedish, else `en`, the
 * app's fallback (the languages it ships, as the phone's `ai_language_tag`
 * says). One answer for everything iOS-side that names the language: sync's
 * `Accept-Language`, catalogue names and their exercise rows. A new app
 * language adds its tag here (CLAUDE.md, "Adding an app language").
 */
fun iosAppLanguage(): String =
    if (NSLocale.preferredLanguages.firstOrNull()?.toString()?.startsWith("sv") == true) "sv" else "en"
