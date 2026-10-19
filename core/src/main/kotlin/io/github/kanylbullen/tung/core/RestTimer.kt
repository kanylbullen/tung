package io.github.kanylbullen.tung.core

/** Rest between sets when neither the exercise nor the user has said otherwise. */
const val DEFAULT_REST_SECONDS = 90

/** The rest setting moves in steps of this many seconds, and "+30 s" adds this much. */
const val REST_STEP_SECONDS = 30

/** The longest rest the setting offers. 0 is the shortest and means "off". */
const val MAX_REST_SECONDS = 300

/**
 * A rest countdown, as two timestamps rather than a ticking number: it ends at
 * [endsAtMillis] (epoch millis, wall clock) and was [durationMillis] long in
 * total, including any time added. What is left is always computed from the
 * clock, so the timer survives the screen being off, the app being in the
 * background and the process being killed, as long as the two numbers are
 * stored somewhere.
 *
 * There is no "idle" state in here: no timer is `null`, which is also what
 * [skip] returns.
 */
data class RestTimer(val endsAtMillis: Long, val durationMillis: Long) {

    /** Millis left at [nowMillis], never negative. */
    fun remainingAt(nowMillis: Long): Long = (endsAtMillis - nowMillis).coerceAtLeast(0L)

    fun isRunningAt(nowMillis: Long): Boolean = nowMillis < endsAtMillis

    fun isFinishedAt(nowMillis: Long): Boolean = !isRunningAt(nowMillis)

    /**
     * "+30 s": a running timer ends [seconds] later; a finished one starts over
     * from [nowMillis] with [seconds] left, since adding to a past end would
     * still be in the past.
     */
    fun plus(nowMillis: Long, seconds: Int = REST_STEP_SECONDS): RestTimer {
        val extra = seconds * 1000L
        return if (isRunningAt(nowMillis)) {
            RestTimer(endsAtMillis + extra, durationMillis + extra)
        } else {
            RestTimer(nowMillis + extra, extra)
        }
    }

    /** Skipping ends the rest: there is no timer afterwards. */
    fun skip(): RestTimer? = null

    companion object {
        /** A timer of [seconds] starting at [nowMillis], or null when the rest is off (0 or less). */
        fun start(nowMillis: Long, seconds: Int): RestTimer? {
            if (seconds <= 0) return null
            val duration = seconds * 1000L
            return RestTimer(nowMillis + duration, duration)
        }
    }
}

/** The rest that applies to an exercise: its own setting, else the user's default. */
fun restSecondsFor(exerciseRestSeconds: Int?, defaultSeconds: Int): Int =
    (exerciseRestSeconds ?: defaultSeconds).coerceIn(0, MAX_REST_SECONDS)

/** One −/+ press in the rest setting: [steps] times 30 s, kept within 0–300 s. */
fun stepRestSeconds(current: Int, steps: Int): Int =
    (current + steps * REST_STEP_SECONDS).coerceIn(0, MAX_REST_SECONDS)
