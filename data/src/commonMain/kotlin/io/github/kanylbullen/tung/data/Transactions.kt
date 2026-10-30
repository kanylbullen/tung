package io.github.kanylbullen.tung.data

import androidx.room.RoomDatabase

/**
 * One database transaction around [block], which runs the DAOs' suspend
 * functions inside it: Room's `withTransaction` on Android (unchanged
 * behaviour for the phone and the watch), a writer connection's immediate
 * transaction on iOS, where that function does not exist. Every rule in
 * `data/` that writes more than one row runs in one of these.
 */
expect suspend fun <R> RoomDatabase.transaction(block: suspend () -> R): R
