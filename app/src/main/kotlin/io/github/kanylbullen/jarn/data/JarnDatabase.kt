package io.github.kanylbullen.jarn.data

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
abstract class JarnDatabase : RoomDatabase() {
    abstract fun exerciseDao(): ExerciseDao
    abstract fun workoutDao(): WorkoutDao

    companion object {
        fun build(context: Context): JarnDatabase =
            Room.databaseBuilder(context, JarnDatabase::class.java, "jarn.db")
                .addCallback(SeedCallback)
                .build()
    }

    /** Runs once, inside the transaction that creates the schema. */
    private object SeedCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            SeedExercises.all.forEach { (name, muscle, equipment) ->
                db.execSQL(
                    "INSERT INTO exercise (name, muscleGroup, equipment, isCustom) VALUES (?, ?, ?, 0)",
                    arrayOf<Any>(name, muscle.key, equipment.key),
                )
            }
        }
    }
}
