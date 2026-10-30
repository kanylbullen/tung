package io.github.kanylbullen.tung.core.importing

import io.github.kanylbullen.tung.core.nameBasedUuid
import kotlinx.datetime.TimeZone

/**
 * What an import adapter read from another app's export, as plain data:
 * everything in kilograms and epoch milliseconds, every row with its
 * deterministic id ([importId]), nothing resolved against Tung's exercises
 * yet. Exercises are named as the source names them; [importExerciseNames]
 * lists them and `resolveImportNames` decides what each one is.
 */
data class ImportBundle(
    /** The adapter's key, stored on every imported workout as `Workout.importSource`: `jefit`. */
    val source: String,
    /** Oldest first. */
    val workouts: List<ImportedWorkout>,
    val routines: List<ImportedRoutine>,
    /** Names the source marks as the user's own exercises (Jefit's CUSTOM EXERCISES), trimmed. */
    val customExercises: Set<String>,
    /** What was left out, and how often, per reason: sessions, logs or sets, as the reason says. */
    val skipped: Map<SkipReason, Int>,
    /**
     * Imported sets the source had a type for other than an ordinary one,
     * by its name (`warm-up`, `drop`, `failure`). Tung has no set types yet,
     * so they are imported as ordinary logged sets; the preview says so.
     */
    val setTypes: Map<String, Int>,
    /** How the source's per-set rows were used, so every one can be accounted for. */
    val setRows: SetRowAccount = SetRowAccount(),
) {
    val setCount: Int get() = workouts.sumOf { w -> w.exercises.sumOf { it.sets.size } }

    /** The first and last workout's start, or null with none. */
    val dateRange: LongRange? get() = if (workouts.isEmpty()) null else workouts.minOf { it.startedAt }..workouts.maxOf { it.startedAt }
}

data class ImportedWorkout(
    val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val exercises: List<ImportedExercise>,
)

/** One exercise of a workout ([id] is its `WorkoutExercise` id) with its sets in order. */
data class ImportedExercise(val id: String, val name: String, val sets: List<ImportedSet>)

/** A logged set: ticked, with reps > 0 (an import never brings unlogged sets). */
data class ImportedSet(val id: String, val weightKg: Double, val reps: Int)

data class ImportedRoutine(val id: String, val name: String, val exercises: List<ImportedRoutineExercise>)

data class ImportedRoutineExercise(val id: String, val name: String, val targetSets: Int, val targetReps: Int?)

/** How many of the source's set rows were read, used, and skipped (with a reason each). */
data class SetRowAccount(val read: Int = 0, val used: Int = 0, val skipped: Int = 0)

/** Why something in the export was not imported. Each counts what its name says. */
enum class SkipReason {
    /** A session with no exercise logs (and no logs of its day to adopt). */
    SESSION_WITHOUT_EXERCISES,

    /** A session with no usable start time: neither its own nor its logs'. */
    SESSION_WITHOUT_TIME,

    /** An exercise log of a cardio exercise: Tung logs weight × reps only. */
    CARDIO,

    /** A row of the source's separate cardio log. */
    CARDIO_LOG,

    /** An exercise log with no set that has reps. */
    LOG_WITHOUT_SETS,

    /** A set with 0 or no reps: it would not count as logged in Tung. */
    SET_WITHOUT_REPS,

    /** A set whose weight is outside 0–999 kg, or that could not be read. */
    SET_UNREADABLE,

    /** A set row whose exercise log is not in the export. */
    SET_WITHOUT_LOG,

    /** A data row that repeated its table's header. */
    STRAY_HEADER,

    /** A routine day with no exercises. */
    ROUTINE_WITHOUT_EXERCISES,

    /** A routine day with the same name and exercises as one already imported. */
    DUPLICATE_ROUTINE,
}

/**
 * The id of an imported row: a name-based UUID from
 * `tung:import:<source>:<kind>:<parts>`, so importing the same export again
 * produces the same ids and adds nothing, on any device.
 */
fun importId(source: String, kind: String, vararg parts: String): String =
    nameBasedUuid("tung:import:$source:$kind:${parts.joinToString(":")}")

/** Every exercise name the bundle uses, in workouts and routines, trimmed, once each. */
fun ImportBundle.importExerciseNames(): Set<String> =
    (workouts.flatMap { w -> w.exercises.map { it.name } } + routines.flatMap { r -> r.exercises.map { it.name } }).toCollection(LinkedHashSet())

/** An adapter for one source format: recognises its export and parses it. Pure. */
interface ImportAdapter {
    val source: String

    /** True when [text] looks like this source's export. */
    fun recognises(text: String): Boolean

    /** Parses [text]; dates without a time are read in [zone]. */
    fun parse(text: String, zone: TimeZone): ImportBundle
}

/** The adapters Tung has, in the order they are tried. */
val IMPORT_ADAPTERS: List<ImportAdapter> = listOf(JefitImport)

/** The adapter for [text], or null when no adapter recognises it. */
fun adapterFor(text: String): ImportAdapter? = IMPORT_ADAPTERS.firstOrNull { it.recognises(text) }
