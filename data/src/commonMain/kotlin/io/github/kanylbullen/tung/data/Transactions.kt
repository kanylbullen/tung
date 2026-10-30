package io.github.kanylbullen.tung.data

import androidx.room.RoomDatabase

/**
 * One database transaction around [block], which runs the DAOs' suspend
 * functions inside it: Room's `withTransaction` on Android, a writer
 * connection's immediate transaction on iOS, where that function does not
 * exist. Every rule in `data/` that writes more than one row runs in one of
 * these. (In a multiplatform module Room generates its driver-style DAO code
 * for the Android target too, run in compatibility mode over the Android
 * open helper; the schema and its identity hash are the same.)
 */
expect suspend fun <R> RoomDatabase.transaction(block: suspend () -> R): R
