package io.github.kanylbullen.tung.data

import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * A writer connection's immediate transaction; the DAOs called in [block]
 * join it. Room runs the block under the database's own job, not the
 * caller's, so a caller cancelled meanwhile would still commit: the caller's
 * [Job] is checked before the commit, and its cancellation rolls back, as
 * Android's `withTransaction` does.
 */
actual suspend fun <R> RoomDatabase.transaction(block: suspend () -> R): R {
    val caller = coroutineContext[Job]
    return useWriterConnection { transactor ->
        transactor.immediateTransaction {
            val result = block()
            caller?.ensureActive()
            result
        }
    }
}
