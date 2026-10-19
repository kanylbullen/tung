package io.github.kanylbullen.tung.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import io.github.kanylbullen.tung.core.LoggedSet

@Entity(tableName = "exercise", indices = [Index("name")])
data class Exercise(
    @PrimaryKey val id: String,
    /** What the user typed for a custom exercise; the English fallback for a catalogue one. */
    val name: String,
    /**
     * Stable catalogue key, shown via the string resource `ex_<nameKey>`
     * (see `ui/common/ExerciseNames.kt`). Null for custom exercises.
     */
    val nameKey: String? = null,
    /** One of [io.github.kanylbullen.tung.core.MuscleGroup] keys. */
    val muscleGroup: String,
    /** One of [io.github.kanylbullen.tung.core.Equipment] keys. */
    val equipment: String,
    val isCustom: Boolean,
    /** Epoch millis of the last write; set by the repository. */
    val updatedAt: Long,
)

@Entity(tableName = "workout", indices = [Index("endedAt"), Index("startedAt"), Index("routineId")])
data class Workout(
    @PrimaryKey val id: String,
    val startedAt: Long,
    /** Null while the workout is in progress. At most one such row is expected. */
    val endedAt: Long? = null,
    val note: String? = null,
    /**
     * The routine this workout was started from, if any. Deliberately not a
     * foreign key: deleting a routine must never delete or change history, so
     * this can point at a routine that no longer exists.
     */
    val routineId: String? = null,
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

/** A saved workout template: exercises in order, each with a number of sets. */
@Entity(tableName = "routine", indices = [Index("lastUsedAt")])
data class Routine(
    @PrimaryKey val id: String,
    val name: String,
    /** Epoch millis of the last write; set by the repository. */
    val updatedAt: Long,
    /** When a workout was last started from it; null if never. */
    val lastUsedAt: Long? = null,
)

@Entity(
    tableName = "routine_exercise",
    foreignKeys = [
        ForeignKey(
            entity = Routine::class,
            parentColumns = ["id"],
            childColumns = ["routineId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Exercise::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("routineId"), Index("exerciseId")],
)
data class RoutineExercise(
    @PrimaryKey val id: String,
    val routineId: String,
    val exerciseId: String,
    val position: Int,
    val targetSets: Int,
    /** The reps the routine aims at; null when it has none. */
    val targetReps: Int?,
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

/** A routine's exercise with its catalogue row. */
data class RoutineExerciseWithExercise(
    @Embedded val routineExercise: RoutineExercise,
    @Relation(parentColumn = "exerciseId", entityColumn = "id")
    val exercise: Exercise,
)

/** One row in a routine list, aggregated in SQL. */
data class RoutineSummaryRow(
    val id: String,
    val name: String,
    val lastUsedAt: Long?,
    val exerciseCount: Int,
)
