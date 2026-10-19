package io.github.kanylbullen.jarn.domain

/**
 * What a new set should start out as.
 *
 * The previous set in this session wins: if you just did 80 × 8, the next set
 * is most likely 80 × 8 again. Only for the first set of an exercise do we fall
 * back to what you did at the same position last time. Returns null when there
 * is nothing to go on.
 *
 * @param position zero-based index of the set being added.
 */
fun prefillSet(
    lastInThisWorkout: LoggedSet?,
    previousSession: List<LoggedSet>,
    position: Int,
): LoggedSet? = lastInThisWorkout ?: previousSession.getOrNull(position)
