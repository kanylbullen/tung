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

    /**
     * The volume of every session in an ISO week, summed: one point per week
     * that has a session, dated its Monday ([trendPoints]). A week's number,
     * so a single session has none ([SessionPoint.value] is null).
     */
    WEEKLY_VOLUME,
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
        ProgressMetric.WEEKLY_VOLUME -> null
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

    /** Twelve months back; "1Y" on the exercise screen, "12M" on the Progress tab's rows. */
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

/** One point of a trend: a session's day, or a week's Monday for [ProgressMetric.WEEKLY_VOLUME], and the value. */
data class TrendPoint(val date: LocalDate, val value: Double)

/**
 * [metric] over these sessions as they are (oldest first): one point per
 * session that has a value, or per ISO week that has a session for
 * [ProgressMetric.WEEKLY_VOLUME], whose value is the week's sessions' volume.
 */
fun List<SessionPoint>.trendPoints(metric: ProgressMetric): List<TrendPoint> = when (metric) {
    ProgressMetric.WEEKLY_VOLUME ->
        groupBy { weekStart(it.date) }
            .map { (week, sessions) -> TrendPoint(week, sessions.sumOf { it.volumeKg }) }
            .sortedBy { it.date }
    else -> mapNotNull { s -> s.value(metric)?.let { TrendPoint(s.date, it) } }
}

/**
 * [metric] over [range] back from [today], oldest first: the sessions
 * [within] it, or for [ProgressMetric.WEEKLY_VOLUME] every week from the one
 * the range's first day falls in, counted whole, so the first point is not
 * part of a week.
 */
fun trendPoints(sessions: List<SessionPoint>, metric: ProgressMetric, range: ProgressRange, today: LocalDate): List<TrendPoint> {
    if (metric != ProgressMetric.WEEKLY_VOLUME) return sessions.within(range, today).trendPoints(metric)
    val start = range.startOn(today)?.let(::weekStart) ?: return sessions.trendPoints(metric)
    return sessions.filter { it.date >= start }.trendPoints(metric)
}

/** From the first point to the last; null with fewer than two, since one point is no trend. */
fun List<TrendPoint>.change(): Change? = if (size < 2) null else Change(first().value, last().value)

/**
 * How [metric] moved over [points] (already cut to a range, oldest first):
 * from the first session that has a value to the last (from the first week
 * to the last for [ProgressMetric.WEEKLY_VOLUME]). Null with fewer than two
 * such points, since one point is no trend.
 */
fun change(points: List<SessionPoint>, metric: ProgressMetric): Change? = points.trendPoints(metric).change()

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

/**
 * The spans the compact trends offer, the Progress tab's exercise rows and
 * the sheet inside a workout: 3 and 12 months ([ProgressRange.ONE_YEAR]).
 */
val TREND_RANGES: List<ProgressRange> = listOf(ProgressRange.THREE_MONTHS, ProgressRange.ONE_YEAR)

/** The metrics the compact trends offer: estimated 1RM, and volume per session or per week. */
val TREND_METRICS: List<ProgressMetric> = listOf(ProgressMetric.E1RM, ProgressMetric.VOLUME, ProgressMetric.WEEKLY_VOLUME)

/**
 * What an exercise row shows for a metric and a span: the latest value of
 * all (the last session's, or the last trained week's), the values in the
 * span, oldest first, and their [change].
 */
data class RowTrend(val latest: Double?, val values: List<Double>, val change: Change?)

/** This exercise's [metric] over [range] back from [today], for its row on the Progress tab. */
fun ExerciseProgress.trend(metric: ProgressMetric, range: ProgressRange, today: LocalDate): RowTrend {
    val inRange = trendPoints(sessions, metric, range, today)
    val latest = if (metric == ProgressMetric.WEEKLY_VOLUME) {
        sessions.lastOrNull()?.let { last -> sessions.filter { weekStart(it.date) == weekStart(last.date) }.sumOf { it.volumeKg } }
    } else {
        sessions.lastOrNull { it.value(metric) != null }?.value(metric)
    }
    return RowTrend(latest, inRange.map { it.value }, inRange.change())
}
