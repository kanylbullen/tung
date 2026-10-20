package io.github.kanylbullen.tung.core.health

/** One logged set as the Health Connect session sees it: which catalogue exercise (null for custom/extended) and how many reps. */
data class HealthSet(val exerciseKey: String?, val reps: Int)

/** One `ExerciseSegment`: a set of an exercise Health Connect knows, with its reps. */
data class HealthSegment(
    val startMillis: Long,
    val endMillis: Long,
    val type: HealthSegmentType,
    val repetitions: Int,
)

/**
 * The `ExerciseSessionRecord` the phone writes for a finished workout, as
 * plain data: `data/` turns it into the library's record.
 * [clientRecordId] makes the write idempotent on Health Connect's side too
 * (a second insert with the same id replaces the first instead of adding a
 * session), on top of the workout's own `healthConnectId`.
 */
data class HealthSession(
    val clientRecordId: String,
    val startMillis: Long,
    val endMillis: Long,
    val title: String,
    val segments: List<HealthSegment>,
)

/** The client record id of [workoutId]'s session: stable, so a retry replaces rather than duplicates. */
fun healthClientRecordId(workoutId: String): String = "tung:workout:$workoutId"

/**
 * The session for a workout that ran from [startMillis] to [endMillis] with
 * [sets] logged, in workout order (exercise position, then set position).
 *
 * Tung keeps no time per set, so the interval is split evenly into one slot
 * per logged set, in that order; a set whose exercise has a Health Connect
 * type ([healthSegmentFor]) becomes a segment over its slot, and a set
 * without one leaves its slot unsegmented. Health Connect needs a session
 * that ends after it starts and segments of positive length, so a workout
 * shorter than one second per set is stretched to that.
 */
fun planHealthSession(
    workoutId: String,
    startMillis: Long,
    endMillis: Long,
    title: String,
    sets: List<HealthSet>,
): HealthSession {
    val end = maxOf(endMillis, startMillis + MIN_SLOT_MILLIS * maxOf(1, sets.size))
    val slot = if (sets.isEmpty()) 0L else (end - startMillis) / sets.size
    val segments = sets.mapIndexedNotNull { i, set ->
        val type = healthSegmentFor(set.exerciseKey) ?: return@mapIndexedNotNull null
        val from = startMillis + i * slot
        val to = if (i == sets.lastIndex) end else from + slot
        HealthSegment(from, to, type, set.reps.coerceAtLeast(0))
    }
    return HealthSession(healthClientRecordId(workoutId), startMillis, end, title, segments)
}

private const val MIN_SLOT_MILLIS = 1_000L
