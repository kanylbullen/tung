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
    /**
     * Rest between sets of this exercise, in seconds; 0 means no rest timer.
     * Null follows the user's default (90 s unless changed).
     */
    val restSeconds: Int? = null,
    /** Changed here since the last push: set with `updatedAt` by every write, cleared by sync (CLAUDE.md, "Sync"). */
    val dirty: Boolean = true,
)

@Entity(
    tableName = "workout",
    indices = [Index("endedAt"), Index("startedAt"), Index("routineId"), Index("programDayId"), Index("importBatchId")],
)
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
    /**
     * The program day this workout was started from, if any; it decides which
     * day a rotating program suggests next. Not a foreign key, like [routineId]:
     * removing a day or deleting a program never touches history.
     */
    val programDayId: String? = null,
    /** Changed here since the last push: set with `updatedAt` by every write, cleared by sync (CLAUDE.md, "Sync"). */
    val dirty: Boolean = true,
    /**
     * Who started it, one of [io.github.kanylbullen.tung.core.StartedBy]
     * (`phone`, `watch`, `agent`, …), stamped by the command path that created
     * it and synced as `started_by`; null when unknown. Never changed after.
     */
    val startedBy: String? = null,
    /**
     * Read from Health Connect after the workout was finished on a phone that
     * opted in (CLAUDE.md, "Health Connect"): average and maximum heart rate in
     * bpm and active kilocalories over [startedAt]..[endedAt]. Null when not
     * read or nothing was there. Synced; a null from a peer keeps the stored value.
     */
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    val activeCalories: Int? = null,
    /**
     * The id of the `ExerciseSessionRecord` written to Health Connect for this
     * workout. Set once; while it is set no device writes the session again.
     * Synced like the vitals. Schema version 2.
     */
    val healthConnectId: String? = null,
    /**
     * The app this workout was imported from (`core.importing.ImportBundle.source`,
     * `jefit`), null for one logged in Tung. An imported workout is history:
     * it counts for "Last" and the pre-fill, but never shows as a new record.
     * Set once by the import; synced as `import_source`. Schema version 3.
     */
    val importSource: String? = null,
    /** The import run that wrote it, so "Undo import" removes exactly that run. Synced as `import_batch_id`. */
    val importBatchId: String? = null,
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
    /** Changed here since the last push: set with `updatedAt` by every write, cleared by sync (CLAUDE.md, "Sync"). */
    val dirty: Boolean = true,
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
    /** Changed here since the last push: set with `updatedAt` by every write, cleared by sync (CLAUDE.md, "Sync"). */
    val dirty: Boolean = true,
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
    /** Changed here since the last push: set with `updatedAt` by every write, cleared by sync (CLAUDE.md, "Sync"). */
    val dirty: Boolean = true,
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
    /** Changed here since the last push: set with `updatedAt` by every write, cleared by sync (CLAUDE.md, "Sync"). */
    val dirty: Boolean = true,
)

/**
 * An ordered list of routines with a [mode]: fixed weekdays or rotating. At
 * most one program [isActive]; that one gives Home its "Today".
 */
@Entity(tableName = "program", indices = [Index("isActive")])
data class Program(
    @PrimaryKey val id: String,
    val name: String,
    /** A [io.github.kanylbullen.tung.core.ProgramMode] key: "fixed" or "rotating". */
    val mode: String,
    val isActive: Boolean,
    /** Epoch millis of the last write; set by the repository. */
    val updatedAt: Long,
    /** Changed here since the last push: set with `updatedAt` by every write, cleared by sync (CLAUDE.md, "Sync"). */
    val dirty: Boolean = true,
)

/**
 * One day of a program: a routine at a [position], and in fixed mode the
 * weekdays it falls on.
 *
 * Both foreign keys cascade. Deleting the program deletes its days, and so
 * does deleting the routine: a day without its routine has nothing to start,
 * so the day goes with it rather than being left empty. Workouts started from
 * a day keep their [Workout.programDayId], which is not a foreign key.
 */
@Entity(
    tableName = "program_day",
    foreignKeys = [
        ForeignKey(
            entity = Program::class,
            parentColumns = ["id"],
            childColumns = ["programId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Routine::class,
            parentColumns = ["id"],
            childColumns = ["routineId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("programId"), Index("routineId")],
)
data class ProgramDay(
    @PrimaryKey val id: String,
    val programId: String,
    val routineId: String,
    val position: Int,
    /**
     * The ISO weekdays (1 Monday … 7 Sunday) this day is trained on in fixed
     * mode, one bit each with Monday in bit 0 (`core.weekdaysToMask`). 0 is
     * none. Kept, but unused, in rotating mode.
     */
    val weekdays: Int,
    /** Epoch millis of the last write; set by the repository. */
    val updatedAt: Long,
    /** Changed here since the last push: set with `updatedAt` by every write, cleared by sync (CLAUDE.md, "Sync"). */
    val dirty: Boolean = true,
)

/**
 * A row deleted on this device, so sync can tell the server: [tableName] is
 * the wire key of its table (`core.sync.SyncTable.key`), [deletedAt] the
 * delete's time. Written by the repositories in the same transaction as the
 * delete, for the row and every row the delete cascades to; existing queries
 * never see it. [dirty] until pushed.
 */
@Entity(tableName = "tombstones", primaryKeys = ["tableName", "id"], indices = [Index("dirty")])
data class Tombstone(
    val tableName: String,
    val id: String,
    val deletedAt: Long,
    val dirty: Boolean = true,
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

/**
 * One row in the history list, aggregated in SQL. [routineName] is the routine
 * the workout was started from, if it still exists; the first two exercises
 * (name and catalogue key, in workout order) title a row without one.
 */
data class WorkoutSummaryRow(
    val id: String,
    val startedAt: Long,
    val endedAt: Long?,
    val exerciseCount: Int,
    val volumeKg: Double,
    val routineName: String? = null,
    val firstExerciseName: String? = null,
    val firstExerciseKey: String? = null,
    val secondExerciseName: String? = null,
    val secondExerciseKey: String? = null,
)

/** One exercise of a finished workout, for the picker's history (`WorkoutDao.recentExerciseOrder`). */
data class WorkoutExerciseOrderRow(
    val workoutId: String,
    val startedAt: Long,
    val exerciseId: String,
)

/** One counted set of an exercise in a finished workout, for its exercise page (`WorkoutDao.observeExerciseHistory`). */
data class ExerciseHistoryRow(
    val workoutId: String,
    val workoutExerciseId: String,
    val startedAt: Long,
    val exercisePosition: Int,
    val setPosition: Int,
    val weightKg: Double,
    val reps: Int,
)

/** A routine's exercise with its catalogue row. */
data class RoutineExerciseWithExercise(
    @Embedded val routineExercise: RoutineExercise,
    @Relation(parentColumn = "exerciseId", entityColumn = "id")
    val exercise: Exercise,
)

/** A routine's exercise with its target sets, for the muscles a routine works (`routineMuscles`). */
data class RoutineExerciseLink(val routineId: String, val exerciseId: String, val targetSets: Int)

/** One row in a routine list, aggregated in SQL. */
data class RoutineSummaryRow(
    val id: String,
    val name: String,
    val lastUsedAt: Long?,
    val exerciseCount: Int,
)

/** One row in the program list, aggregated in SQL. */
data class ProgramSummaryRow(
    val id: String,
    val name: String,
    val mode: String,
    val isActive: Boolean,
    val dayCount: Int,
)

/** A program day with the name of its routine. */
data class ProgramDayRow(
    val id: String,
    val programId: String,
    val routineId: String,
    val position: Int,
    val weekdays: Int,
    val routineName: String,
)
