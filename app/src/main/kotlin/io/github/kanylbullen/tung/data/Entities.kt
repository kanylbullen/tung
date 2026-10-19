package io.github.kanylbullen.tung.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import io.github.kanylbullen.tung.domain.LoggedSet

@Entity(tableName = "exercise", indices = [Index("name")])
data class Exercise(
    @PrimaryKey val id: String,
    /** What the user typed for a custom exercise; the Swedish fallback for a catalogue one. */
    val name: String,
    /**
     * Stable catalogue key, shown via the string resource `ex_<nameKey>`
     * (see `ui/common/ExerciseNames.kt`). Null for custom exercises.
     */
    val nameKey: String? = null,
    /** One of [io.github.kanylbullen.tung.domain.MuscleGroup] keys. */
    val muscleGroup: String,
    /** One of [io.github.kanylbullen.tung.domain.Equipment] keys. */
    val equipment: String,
    val isCustom: Boolean,
    /** Epoch millis of the last write; set by the repository. */
    val updatedAt: Long,
)

@Entity(tableName = "workout", indices = [Index("endedAt"), Index("startedAt")])
data class Workout(
    @PrimaryKey val id: String,
    val startedAt: Long,
    /** Null while the workout is in progress. At most one such row is expected. */
    val endedAt: Long? = null,
    val note: String? = null,
    /** Epoch millis of the last write; set by the repository. */
    val updatedAt: Long,
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
    @PrimaryKey val id: String,
    val workoutId: String,
    val exerciseId: String,
    val position: Int,
    /** Epoch millis of the last write; set by the repository. */
    val updatedAt: Long,
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
    @PrimaryKey val id: String,
    val workoutExerciseId: String,
    val position: Int,
    val weightKg: Double,
    val reps: Int,
    val completed: Boolean,
    /** Epoch millis of the last write; set by the repository. */
    val updatedAt: Long,
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
    val id: String,
    val startedAt: Long,
    val endedAt: Long?,
    val exerciseCount: Int,
    val volumeKg: Double,
)
