package io.github.kanylbullen.tung.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/** `updatedAt` of a seeded catalogue row that nobody has changed. */
private const val CATALOG_UPDATED_AT = 0L

@Database(
    entities = [Exercise::class, Workout::class, WorkoutExercise::class, SetEntry::class],
    version = 1,
    exportSchema = false,
)
abstract class TungDatabase : RoomDatabase() {
    abstract fun exerciseDao(): ExerciseDao
    abstract fun workoutDao(): WorkoutDao

    companion object {
        fun build(context: Context): TungDatabase =
            Room.databaseBuilder(context, TungDatabase::class.java, "tung.db")
                .addCallback(SeedCallback)
                .build()
    }

    /**
     * Runs once, inside the transaction that creates the schema. Catalogue rows
     * get the id [catalogExerciseId] derives from their key and a fixed
     * [CATALOG_UPDATED_AT], so a freshly seeded catalogue row is identical on
     * every device.
     */
    private object SeedCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            SeedExercises.all.forEach { seed ->
                db.execSQL(
                    "INSERT INTO exercise (id, name, nameKey, muscleGroup, equipment, isCustom, updatedAt) " +
                        "VALUES (?, ?, ?, ?, ?, 0, ?)",
                    arrayOf<Any>(
                        catalogExerciseId(seed.key),
                        seed.name,
                        seed.key,
                        seed.muscle.key,
                        seed.equipment.key,
                        CATALOG_UPDATED_AT,
                    ),
                )
            }
        }
    }
}
