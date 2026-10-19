package io.github.kanylbullen.tung.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

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

    /** Runs once, inside the transaction that creates the schema. */
    private object SeedCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            SeedExercises.all.forEach { seed ->
                db.execSQL(
                    "INSERT INTO exercise (name, nameKey, muscleGroup, equipment, isCustom) VALUES (?, ?, ?, ?, 0)",
                    arrayOf<Any>(seed.name, seed.key, seed.muscle.key, seed.equipment.key),
                )
            }
        }
    }
}
