package io.github.kanylbullen.jarn.domain

/**
 * The two numbers a set is made of. Domain functions work on this rather than
 * on Room entities so they can be tested without Android.
 */
data class LoggedSet(val weightKg: Double, val reps: Int) {
    val volume: Double get() = weightKg * reps
}

/** A set counts once it is ticked and has at least one rep. Everything else is a placeholder. */
fun isLogged(completed: Boolean, reps: Int): Boolean = completed && reps > 0
