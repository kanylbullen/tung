package io.github.kanylbullen.tung.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * The iPhone's database at [path] (a file in the app's documents directory),
 * over SQLite compiled into the app ([BundledSQLiteDriver]), queries on
 * `Dispatchers.IO`.
 */
fun TungDatabase.Companion.build(path: String): TungDatabase =
    Room.databaseBuilder<TungDatabase>(name = path)
        .addMigrations(*ALL_MIGRATIONS)
        .addCallback(seedCallback())
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

/** [DatabaseSchema.MIGRATIONS] over `SQLiteConnection`, the driver API. */
actual val ALL_MIGRATIONS: Array<Migration> = DatabaseSchema.MIGRATIONS.map { step ->
    object : Migration(step.from, step.to) {
        override fun migrate(connection: SQLiteConnection) {
            step.statements.forEach(connection::execSQL)
        }
    }
}.toTypedArray()

/** Runs once, inside the transaction that creates the schema ([DatabaseSchema.seedRows]). */
internal actual fun seedCallback(): RoomDatabase.Callback = object : RoomDatabase.Callback() {
    override fun onCreate(connection: SQLiteConnection) {
        super.onCreate(connection)
        DatabaseSchema.seedRows().forEach { row ->
            connection.prepare(DatabaseSchema.SEED_INSERT).use { statement ->
                statement.bindText(1, row.id)
                statement.bindText(2, row.name)
                statement.bindText(3, row.nameKey)
                statement.bindText(4, row.muscleGroup)
                statement.bindText(5, row.equipment)
                statement.bindLong(6, row.updatedAt)
                statement.step()
            }
        }
    }
}
