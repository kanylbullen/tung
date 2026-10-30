package io.github.kanylbullen.tung.data

import kotlinx.datetime.Clock

/** The wall clock in epoch millis, the default `clock` of the repositories and the import: the instant `System.currentTimeMillis()` gives on the JVM, from kotlinx-datetime on every platform. */
fun systemMillis(): Long = Clock.System.now().toEpochMilliseconds()
