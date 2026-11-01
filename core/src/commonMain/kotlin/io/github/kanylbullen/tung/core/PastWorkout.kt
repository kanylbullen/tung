package io.github.kanylbullen.tung.core

/**
 * A workout started more than this long ago counts as one that was not
 * finished in time (forgotten, or logged after the fact on another device):
 * the workout screen shows its date and start instead of an elapsed timer,
 * and finishing it asks how long it lasted rather than ending it now. A
 * workout started from "Log past workout" counts so from the start,
 * whatever its age (the device remembers it).
 */
const val PAST_WORKOUT_AFTER_MILLIS: Long = 4L * 60 * 60 * 1000

/** How far ahead of the store's clock a workout's start or end may lie: a device clock that runs a little fast. */
const val WORKOUT_CLOCK_SKEW_MILLIS: Long = 60_000L

/** The longest a workout may last when its times are set by hand (`set_workout_time`). */
const val MAX_WORKOUT_DURATION_MILLIS: Long = 24L * 60 * 60 * 1000

/** The earliest time a command may give a workout: 2000-01-01T00:00:00Z. */
const val EARLIEST_WORKOUT_MILLIS: Long = 946_684_800_000L

/** True when a workout that started at [startedAtMillis] is past at [nowMillis] by its age alone (see [PAST_WORKOUT_AFTER_MILLIS]). */
fun isPastWorkout(startedAtMillis: Long, nowMillis: Long): Boolean = nowMillis - startedAtMillis > PAST_WORKOUT_AFTER_MILLIS

/** How long a workout logged after the fact lasted, as finishing it first offers: an hour. */
const val DEFAULT_PAST_DURATION_MILLIS: Long = 60L * 60 * 1000

/** The longest duration finishing a past workout offers, and the longest elapsed time it takes as the duration. */
const val MAX_FINISH_DURATION_MILLIS: Long = 6L * 60 * 60 * 1000

/**
 * The duration the finish dialog of a past workout starts at. One logged
 * after the fact ([loggedAfterTheFact]) gets [DEFAULT_PAST_DURATION_MILLIS];
 * a live workout that ran past [PAST_WORKOUT_AFTER_MILLIS] gets the time
 * since its start when that is at most [MAX_FINISH_DURATION_MILLIS] (a long
 * session), else the default (one somebody forgot to finish). Never past
 * [nowMillis], never below zero.
 */
fun finishDurationPreset(startedAtMillis: Long, nowMillis: Long, loggedAfterTheFact: Boolean): Long {
    val elapsed = (nowMillis - startedAtMillis).coerceAtLeast(0L)
    val preset = if (!loggedAfterTheFact && elapsed <= MAX_FINISH_DURATION_MILLIS) elapsed else DEFAULT_PAST_DURATION_MILLIS
    return preset.coerceAtMost(elapsed)
}
