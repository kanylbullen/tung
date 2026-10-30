package io.github.kanylbullen.tung.data

import androidx.room.RoomDatabase
import androidx.room.withTransaction

actual suspend fun <R> RoomDatabase.transaction(block: suspend () -> R): R = withTransaction(block)
