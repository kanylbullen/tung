package io.github.kanylbullen.jarn.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import io.github.kanylbullen.jarn.domain.LoggedSet

@Entity(tableName = "exercise", indices = [Index("name")])
data class Exercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** One of [io.github.kanylbullen.jarn.domain.MuscleGroup] keys. */
    val muscleGroup: String,
    /** One of [io.github.kanylbullen.jarn.domain.Equipment] keys. */
    val equipment: String,
    val isCustom: Boolean,
)

@Entity(tableName = "workout", indices = [Index("endedAt"), Index("startedAt")])
data class Workout(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    /** Null while the workout is in progress. At most one such row is expected. */
    val endedAt: Long? = null,
    val note: String? = null,
)

@Entity(
    tableName = "workout_exercise",
    foreignKeys = [
        ForeignKey(
            entity = Workout::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Exercise::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("workoutId"), Index("exerciseId")],
)
data class WorkoutExercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val exerciseId: Long,
    val position: Int,
)

@Entity(
    tableName = "set_entry",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutExercise::class,
            parentColumns = ["id"],
            childColumns = ["workoutExerciseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("workoutExerciseId")],
)
data class SetEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutExerciseId: Long,
    val position: Int,
    val weightKg: Double,
    val reps: Int,
    val completed: Boolean,
)

fun SetEntry.toLogged(): LoggedSet = LoggedSet(weightKg, reps)

/** A workout's exercise with its catalogue row and its sets, as one observable unit. */
data class WorkoutExerciseWithSets(
    @Embedded val workoutExercise: WorkoutExercise,
    @Relation(parentColumn = "exerciseId", entityColumn = "id")
    val exercise: Exercise,
    @Relation(parentColumn = "id", entityColumn = "workoutExerciseId")
    val sets: List<SetEntry>,
) {
    val sortedSets: List<SetEntry> get() = sets.sortedBy { it.position }
}

/** One row in the history list, aggregated in SQL. */
data class WorkoutSummaryRow(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val exerciseCount: Int,
    val volumeKg: Double,
)
