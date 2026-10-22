package io.github.kanylbullen.tung.core.progress

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.isLogged
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/** Sets with more reps than this say little about a one-rep max, so they get no estimate. */
const val E1RM_MAX_REPS = 12

/**
 * Estimated one-rep max by Epley: `w × (1 + reps / 30)`, for 1–12 reps; a
 * single is the weight itself. Null outside that range (0 reps is no set, and
 * a set of 15 is endurance, not strength).
 */
fun estimatedOneRepMax(weightKg: Double, reps: Int): Double? = when {
    reps < 1 || reps > E1RM_MAX_REPS -> null
    reps == 1 -> weightKg
    else -> weightKg * (1 + reps / 30.0)
}

fun estimatedOneRepMax(set: LoggedSet): Double? = estimatedOneRepMax(set.weightKg, set.reps)

/**
 * One set as progress reads it: which exercise, which workout and which of its
 * exercise rows, when the workout started, the positions, the numbers, and
 * whether it was ticked. Only logged sets ([isLogged]) count; the rest are
 * dropped by every function here, whatever the caller passes.
 */
data class ProgressSet(
    val exerciseId: String,
    val workoutId: String,
    val workoutExerciseId: String,
    val startedAt: Long,
    val exercisePosition: Int,
    val setPosition: Int,
    val set: LoggedSet,
    val completed: Boolean = true,
) {
    val isLogged: Boolean get() = isLogged(completed, set.reps)
}

/** What a chart can plot for an exercise. */
enum class ProgressMetric {
    /** The session's best estimated one-rep max ([estimatedOneRepMax]). */
    E1RM,

    /** The heaviest weight lifted in the session. */
    TOP_SET,

    /** The session's weight × reps, summed. */
    VOLUME,
}

/**
 * One exercise in one finished workout: the date (the workout's start, in the
 * caller's zone), its logged sets in order, and the three numbers a chart
 * plots. An exercise added to a workout twice is one session.
 */
data class SessionPoint(
    val workoutId: String,
    val startedAt: Long,
    val date: LocalDate,
    val sets: List<LoggedSet>,
    /** The best estimate of the session, null when every set had more than [E1RM_MAX_REPS] reps. */
    val e1rmKg: Double?,
    /** The set [e1rmKg] comes from. */
    val e1rmSet: LoggedSet?,
    /** The heaviest set; of equally heavy ones, the one with most reps. */
    val topSet: LoggedSet,
    val volumeKg: Double,
) {
    fun value(metric: ProgressMetric): Double? = when (metric) {
        ProgressMetric.E1RM -> e1rmKg
        ProgressMetric.TOP_SET -> topSet.weightKg
        ProgressMetric.VOLUME -> volumeKg
    }
}

/** The day [epochMillis] falls on in [zone]. */
fun dateOf(epochMillis: Long, zone: TimeZone): LocalDate = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone).date

/**
 * [sets] of one exercise as sessions, oldest first (by start, then workout id
 * so equal starts keep one order). Unlogged sets are dropped, and a workout
 * left with none is no session.
 */
fun sessionPoints(sets: List<ProgressSet>, zone: TimeZone): List<SessionPoint> =
    sets.filter { it.isLogged }
        .groupBy { it.workoutId }
        .map { (workoutId, rows) ->
            val ordered = rows.sortedWith(
                compareBy<ProgressSet> { it.exercisePosition }.thenBy { it.workoutExerciseId }.thenBy { it.setPosition },
            ).map { it.set }
            val startedAt = rows.first().startedAt
            val best = ordered.mapNotNull { s -> estimatedOneRepMax(s)?.let { s to it } }.maxByOrNull { it.second }
            SessionPoint(
                workoutId = workoutId,
                startedAt = startedAt,
                date = dateOf(startedAt, zone),
                sets = ordered,
                e1rmKg = best?.second,
                e1rmSet = best?.first,
                topSet = ordered.maxWith(compareBy<LoggedSet> { it.weightKg }.thenBy { it.reps }),
                volumeKg = ordered.sumOf { it.volume },
            )
        }
        .sortedWith(compareBy<SessionPoint> { it.startedAt }.thenBy { it.workoutId })

/** How far back a chart looks. */
enum class ProgressRange {
    THREE_MONTHS,
    ONE_YEAR,
    ALL;

    /** The first day in range, counted back from [today]; null for [ALL]. */
    fun startOn(today: LocalDate): LocalDate? = when (this) {
        THREE_MONTHS -> today.minus(3, DateTimeUnit.MONTH)
        ONE_YEAR -> today.minus(1, DateTimeUnit.YEAR)
        ALL -> null
    }
}

/** The sessions from [range]'s first day on (all of them for [ProgressRange.ALL]). */
fun List<SessionPoint>.within(range: ProgressRange, today: LocalDate): List<SessionPoint> {
    val start = range.startOn(today) ?: return this
    return filter { it.date >= start }
}

/** From the first to the last value of a series. */
data class Change(val from: Double, val to: Double) {
    val delta: Double get() = to - from
}

/**
 * How [metric] moved over [points] (already cut to a range, oldest first):
 * from the first session that has a value to the last. Null with fewer than
 * two such sessions, since one point is no trend.
 */
fun change(points: List<SessionPoint>, metric: ProgressMetric): Change? {
    val values = points.mapNotNull { it.value(metric) }
    if (values.size < 2) return null
    return Change(values.first(), values.last())
}

/** One exercise's sessions, for the Progress tab's list. */
data class ExerciseProgress(val exerciseId: String, val sessions: List<SessionPoint>) {
    val lastAt: Long get() = sessions.last().startedAt
}

/**
 * Every exercise in [sets] with at least one session, most recently trained
 * first (ties by exercise id, so the order is stable).
 */
fun exerciseProgress(sets: List<ProgressSet>, zone: TimeZone): List<ExerciseProgress> =
    sets.groupBy { it.exerciseId }
        .mapNotNull { (id, rows) -> sessionPoints(rows, zone).takeIf { it.isNotEmpty() }?.let { ExerciseProgress(id, it) } }
        .sortedWith(compareByDescending<ExerciseProgress> { it.lastAt }.thenBy { it.exerciseId })
