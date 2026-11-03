package io.github.kanylbullen.tung.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Runs [block] to its end on the calling thread and returns what it returned, for tests over
 * the in-memory stores, whose suspend functions never really suspend. `runBlocking` is not in
 * common code (JS has none), and `runTest` cannot hand a value back on JS; a block that does
 * suspend fails here instead of hanging, and such a test belongs in `runTest`.
 */
internal fun <T> runNow(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
    return checkNotNull(outcome) { "the block suspended: use runTest" }.getOrThrow()
}
