package io.github.kanylbullen.tung.core

/**
 * The time-zone database kotlinx-datetime needs in the browser: without it every zone but
 * UTC and the system's is `IllegalTimeZoneException`, so a test in `Europe/Stockholm` fails
 * on JS alone. The web app loads it in its `Main.kt`; here it is loaded before any test runs.
 */
@JsModule("@js-joda/timezone")
@JsNonModule
external object JsJodaTimeZoneModule

@OptIn(ExperimentalStdlibApi::class)
@Suppress("DEPRECATION", "unused")
@EagerInitialization
private val jsJodaTz = JsJodaTimeZoneModule
