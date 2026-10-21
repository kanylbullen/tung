package io.github.kanylbullen.tung.core.progress

import io.github.kanylbullen.tung.core.LoggedSet
import java.time.LocalDate
import java.time.ZoneId

/** The personal records an exercise has, in the order they rank when one set beats several. */
enum class RecordKind {
    HEAVIEST_WEIGHT,
    BEST_E1RM,

    /** The set with most reps (of equal ones, the heavier); a proposed set beats it at its own weight or heavier. */
    MOST_REPS,
    BEST_SESSION_VOLUME,
}

/**
 * One record and when it was set. [value] is kilos for weight, estimate and
 * volume, reps for [RecordKind.MOST_REPS]; [set] is the set behind it (null
 * for a session's volume).
 */
data class PersonalRecord(
    val kind: RecordKind,
    val value: Double,
    val set: LoggedSet?,
    val workoutId: String,
    val startedAt: Long,
    val date: LocalDate,
)

/**
 * An exercise's records over its sessions, plus every logged set ([sets]) so
 * that "most reps at this weight or heavier" can be answered for any weight.
 * A tie never takes a record: the first session to reach a value keeps it.
 */
data class ExerciseRecords(
    val heaviest: PersonalRecord?,
    val bestE1rm: PersonalRecord?,
    val mostReps: PersonalRecord?,
    val bestVolume: PersonalRecord?,
    val sets: List<LoggedSet>,
    /** The session the history starts with; nothing it set is a beaten record. */
    val firstWorkoutId: String?,
) {
    val isEmpty: Boolean get() = sets.isEmpty()

    /** In [RecordKind] order, the ones that exist. */
    val all: List<PersonalRecord> get() = listOfNotNull(heaviest, bestE1rm, mostReps, bestVolume)

    companion object {
        val EMPTY = ExerciseRecords(null, null, null, null, emptyList(), null)
    }
}

private val repsOrder = compareBy<LoggedSet> { it.reps }.thenBy { it.weightKg }

/** The records in [sessions] (oldest first, as [sessionPoints] gives them). */
fun exerciseRecords(sessions: List<SessionPoint>): ExerciseRecords {
    if (sessions.isEmpty()) return ExerciseRecords.EMPTY
    var heaviest: PersonalRecord? = null
    var e1rm: PersonalRecord? = null
    var reps: PersonalRecord? = null
    var volume: PersonalRecord? = null
    for (s in sessions.sortedWith(compareBy<SessionPoint> { it.startedAt }.thenBy { it.workoutId })) {
        fun record(kind: RecordKind, value: Double, set: LoggedSet?) =
            PersonalRecord(kind, value, set, s.workoutId, s.startedAt, s.date)
        if (heaviest == null || s.topSet.weightKg > heaviest.value) {
            heaviest = record(RecordKind.HEAVIEST_WEIGHT, s.topSet.weightKg, s.topSet)
        }
        val estimate = s.e1rmKg
        if (estimate != null && (e1rm == null || estimate > e1rm.value)) {
            e1rm = record(RecordKind.BEST_E1RM, estimate, s.e1rmSet)
        }
        val most = s.sets.maxWith(repsOrder)
        val held = reps?.set
        if (held == null || repsOrder.compare(most, held) > 0) {
            reps = record(RecordKind.MOST_REPS, most.reps.toDouble(), most)
        }
        if (volume == null || s.volumeKg > volume.value) {
            volume = record(RecordKind.BEST_SESSION_VOLUME, s.volumeKg, null)
        }
    }
    return ExerciseRecords(
        heaviest = heaviest,
        bestE1rm = e1rm,
        mostReps = reps,
        bestVolume = volume,
        sets = sessions.flatMap { it.sets },
        firstWorkoutId = sessions.minWith(compareBy<SessionPoint> { it.startedAt }.thenBy { it.workoutId }).workoutId,
    )
}

/** The most reps in one set at [weightKg] or heavier, or null when nothing that heavy was lifted. */
fun mostRepsAtOrAbove(weightKg: Double, sets: List<LoggedSet>): Int? =
    sets.filter { it.reps > 0 && it.weightKg >= weightKg }.maxOfOrNull { it.reps }

/**
 * Which record a set of [weightKg] × [reps] would beat, the highest-ranked
 * one: heavier than ever, else a better estimated 1RM, else more reps than
 * ever at that weight or heavier. Null when it beats none, when it has no reps,
 * or when there is no history (nothing to beat, as with
 * [io.github.kanylbullen.tung.core.isNewRecord]). Equal is not a record.
 * Session volume is not answered: one set does not make a session.
 */
fun beatsRecord(weightKg: Double, reps: Int, records: ExerciseRecords): RecordKind? {
    if (reps < 1 || records.isEmpty) return null
    val heaviest = records.heaviest
    if (heaviest != null && weightKg > heaviest.value) return RecordKind.HEAVIEST_WEIGHT
    val estimate = estimatedOneRepMax(weightKg, reps)
    val best = records.bestE1rm
    if (estimate != null && best != null && estimate > best.value) return RecordKind.BEST_E1RM
    val most = mostRepsAtOrAbove(weightKg, records.sets)
    if (most != null && reps > most) return RecordKind.MOST_REPS
    return null
}

/**
 * The fewest reps at [weightKg] that would beat the best estimated 1RM, or
 * null when none within 1–[E1RM_MAX_REPS] would (the weight is too light) or
 * there is no estimate to beat. "What beats your record" in a workout.
 */
fun repsToBeatE1rm(weightKg: Double, records: ExerciseRecords): Int? {
    val best = records.bestE1rm?.value ?: return null
    return (1..E1RM_MAX_REPS).firstOrNull { reps -> (estimatedOneRepMax(weightKg, reps) ?: 0.0) > best }
}

/** A record with the exercise it belongs to, for the Progress tab's "Recent records". */
data class RecentRecord(val exerciseId: String, val record: PersonalRecord)

/** How many records the Progress tab lists. */
const val RECENT_RECORDS = 5

/**
 * The records still standing across [exercises], newest first, at most
 * [limit]. A record from an exercise's first session is left out (it beat
 * nothing), and of several set by one exercise in one workout only the
 * highest-ranked ([RecordKind] order) is listed.
 */
fun recentRecords(exercises: List<ExerciseProgress>, limit: Int = RECENT_RECORDS): List<RecentRecord> =
    exercises.flatMap { exercise ->
        val records = exerciseRecords(exercise.sessions)
        records.all
            .filter { it.workoutId != records.firstWorkoutId }
            .groupBy { it.workoutId }
            .map { (_, inWorkout) -> RecentRecord(exercise.exerciseId, inWorkout.minBy { it.kind.ordinal }) }
    }.sortedWith(
        compareByDescending<RecentRecord> { it.record.startedAt }
            .thenBy { it.record.kind.ordinal }
            .thenBy { it.exerciseId },
    ).take(limit)

/** Everything the Progress tab shows, from one read of every logged set. */
data class ProgressOverview(
    val weeks: List<WeekBucket>,
    val streak: Int,
    val exercises: List<ExerciseProgress>,
    val recentRecords: List<RecentRecord>,
) {
    val isEmpty: Boolean get() = exercises.isEmpty()

    companion object {
        val EMPTY = ProgressOverview(emptyList(), 0, emptyList(), emptyList())
    }
}

fun progressOverview(sets: List<ProgressSet>, today: LocalDate, zone: ZoneId): ProgressOverview {
    val exercises = exerciseProgress(sets, zone)
    return ProgressOverview(
        weeks = weeklyBuckets(sets, today, zone),
        streak = weeklyStreak(sets, today, zone),
        exercises = exercises,
        recentRecords = recentRecords(exercises),
    )
}
