package io.github.kanylbullen.tung.data

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration

/**
 * The one database of a device, the same schema on every platform. The
 * builders are platform extensions on the companion: `TungDatabase.build(context)`
 * and `buildInMemory(context)` on Android (TungDatabase.android.kt),
 * `TungDatabase.build(path)` on iOS (TungDatabase.ios.kt), each registering
 * [ALL_MIGRATIONS] and [seedCallback]. What they run is common
 * ([DatabaseSchema]): the migrations' statements and the seeded catalogue.
 */
@Database(
    entities = [
        Exercise::class,
        Workout::class,
        WorkoutExercise::class,
        SetEntry::class,
        Routine::class,
        RoutineExercise::class,
        Program::class,
        ProgramDay::class,
        Tombstone::class,
    ],
    // Raised by one with a migration (DatabaseSchema.kt) and data/schemas/<n>.json for every change.
    version = 3,
    exportSchema = true,
)
@ConstructedBy(TungDatabaseConstructor::class)
abstract class TungDatabase : RoomDatabase() {
    abstract fun exerciseDao(): ExerciseDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun routineDao(): RoutineDao
    abstract fun programDao(): ProgramDao
    abstract fun tombstoneDao(): TombstoneDao
    abstract fun syncDao(): SyncDao

    /** The platforms' builders hang off this (see the class comment). */
    companion object
}

/** Room's processor generates the `actual` for every target. */
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object TungDatabaseConstructor : RoomDatabaseConstructor<TungDatabase> {
    override fun initialize(): TungDatabase
}

/**
 * Every migration, oldest first, both database builders register all of
 * them: [DatabaseSchema.MIGRATIONS] run through the platform's SQL API
 * (`SupportSQLiteDatabase` on Android, `SQLiteConnection` on iOS).
 */
expect val ALL_MIGRATIONS: Array<Migration>

/** The callback that seeds the catalogue when the schema is created ([DatabaseSchema.seedRows]). */
internal expect fun seedCallback(): RoomDatabase.Callback
