package io.github.kanylbullen.tung.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** The phone's and the watch's database file, `tung.db`, through Room's Android open helper as before. */
fun TungDatabase.Companion.build(context: Context): TungDatabase =
    Room.databaseBuilder(context, TungDatabase::class.java, "tung.db")
        .addMigrations(*ALL_MIGRATIONS)
        .addCallback(seedCallback())
        .build()

/** The same schema and seed in memory, for the JVM flow tests. The app never calls it. */
@VisibleForTesting
fun TungDatabase.Companion.buildInMemory(context: Context): TungDatabase =
    Room.inMemoryDatabaseBuilder(context, TungDatabase::class.java)
        .addMigrations(*ALL_MIGRATIONS)
        .addCallback(seedCallback())
        .allowMainThreadQueries()
        .build()

/** [DatabaseSchema.MIGRATIONS] over `SupportSQLiteDatabase`, what Room's Android open helper hands a migration. */
actual val ALL_MIGRATIONS: Array<Migration> = DatabaseSchema.MIGRATIONS.map { step ->
    object : Migration(step.from, step.to) {
        override fun migrate(db: SupportSQLiteDatabase) {
            step.statements.forEach(db::execSQL)
        }
    }
}.toTypedArray()

/** 1 → 2, for `MigrationTest`. */
val MIGRATION_1_2: Migration get() = ALL_MIGRATIONS[0]

/** 2 → 3, for `MigrationTest`. */
val MIGRATION_2_3: Migration get() = ALL_MIGRATIONS[1]

/** Runs once, inside the transaction that creates the schema ([DatabaseSchema.seedRows]). */
internal actual fun seedCallback(): RoomDatabase.Callback = object : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        DatabaseSchema.seedRows().forEach { row ->
            db.execSQL(DatabaseSchema.SEED_INSERT, arrayOf<Any>(row.id, row.name, row.nameKey, row.muscleGroup, row.equipment, row.updatedAt))
        }
    }
}
