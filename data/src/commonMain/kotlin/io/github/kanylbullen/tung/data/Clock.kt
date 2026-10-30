package io.github.kanylbullen.tung.data

import kotlinx.datetime.Clock

/** The wall clock in epoch millis, the default `clock` of the repositories and the import: `System.currentTimeMillis()` on every platform. */
fun systemMillis(): Long = Clock.System.now().toEpochMilliseconds()
