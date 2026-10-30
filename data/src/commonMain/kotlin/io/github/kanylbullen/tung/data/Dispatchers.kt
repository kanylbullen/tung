package io.github.kanylbullen.tung.data

import kotlinx.coroutines.CoroutineDispatcher

/**
 * `Dispatchers.IO` of the platform: a JVM member on Android, a native
 * extension on iOS, so common code cannot name it directly. For reads of
 * files and secrets and for the network, as before.
 */
internal expect val ioDispatcher: CoroutineDispatcher
