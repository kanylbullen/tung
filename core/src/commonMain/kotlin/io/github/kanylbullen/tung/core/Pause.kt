package io.github.kanylbullen.tung.core

/**
 * A workout in progress with no input for this long is paused (issue #261):
 * a forgotten workout stops counting. Input is any write to the workout, its
 * exercises or its sets, from any device: the latest `updatedAt` among them
 * ([lastInputAt]). Derived, never stored, so every client computes the same.
 */
const val PAUSE_AFTER_MILLIS: Long = 60L * 60 * 1000

/** What finishing a paused workout adds after its last input, for the last set and leaving the gym. */
const val PAUSE_FINISH_MARGIN_MILLIS: Long = 5L * 60 * 1000

/**
 * When the workout paused: its last input, once that lies more than
 * [PAUSE_AFTER_MILLIS] before [nowMillis]; null while it runs. The last
 * input is never before the start (an empty workout's input is its start)
 * and never after now (a device whose clock runs fast).
 */
fun pausedSince(startedAtMillis: Long, lastInputMillis: Long, nowMillis: Long): Long? {
    val last = maxOf(lastInputMillis, startedAtMillis).coerceAtMost(nowMillis)
    return if (nowMillis - last > PAUSE_AFTER_MILLIS) last else null
}

/** Where finishing a workout paused since [pausedSinceMillis] ends it: [PAUSE_FINISH_MARGIN_MILLIS] after, never after [nowMillis]. */
fun pausedEnd(pausedSinceMillis: Long, nowMillis: Long): Long =
    (pausedSinceMillis + PAUSE_FINISH_MARGIN_MILLIS).coerceAtMost(maxOf(nowMillis, pausedSinceMillis))

/** The latest of [times] (the `updatedAt` of a workout, its exercises and its sets), or null with none. */
fun lastInputAt(times: Iterable<Long>): Long? = times.maxOrNull()
