@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.kanylbullen.tung.data

import kotlin.time.Clock

/** The wall clock in epoch millis, the default `clock` of the repositories and the import: the instant `System.currentTimeMillis()` gives on the JVM, from kotlin.time on every platform. */
fun systemMillis(): Long = Clock.System.now().toEpochMilliseconds()
