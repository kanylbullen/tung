package io.github.kanylbullen.tung.core

/**
 * A workout started this long ago or more is logged after the fact: the
 * workout screen shows its date and start instead of an elapsed timer, and
 * finishing it asks how long it lasted rather than ending it now.
 */
const val PAST_WORKOUT_AFTER_MILLIS: Long = 4L * 60 * 60 * 1000

/** How far ahead of the store's clock a workout's start or end may lie: a device clock that runs a little fast. */
const val WORKOUT_CLOCK_SKEW_MILLIS: Long = 60_000L

/** The longest a workout may last when its times are set by hand (`set_workout_time`). */
const val MAX_WORKOUT_DURATION_MILLIS: Long = 24L * 60 * 60 * 1000

/** The earliest time a command may give a workout: 2000-01-01T00:00:00Z. */
const val EARLIEST_WORKOUT_MILLIS: Long = 946_684_800_000L

/** True when a workout that started at [startedAtMillis] is logged after the fact at [nowMillis] (see [PAST_WORKOUT_AFTER_MILLIS]). */
fun isPastWorkout(startedAtMillis: Long, nowMillis: Long): Boolean = nowMillis - startedAtMillis > PAST_WORKOUT_AFTER_MILLIS
