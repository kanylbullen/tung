package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.Validation
import io.github.kanylbullen.tung.core.command.validate

/** Lower-case canonical UUIDs only, the form core's newId() and catalogExerciseId() produce. */
private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

fun isUuid(value: String): Boolean = UUID_PATTERN.matches(value)

fun requireUuid(value: String, field: String): String {
    if (!isUuid(value)) throw badRequest("$field must be a lower-case UUID, got '$value'.", "invalid_id")
    return value
}

/**
 * Runs core's command validation (core/command/Validation.kt: weight 0–999 kg,
 * reps 0–999, rest 0–3600 s, no blank names), so the API and the app accept
 * the same numbers. [at] says where in the request the values were.
 */
fun requireValid(command: Command, at: String? = null) {
    val result = validate(command)
    if (result is Validation.Invalid) {
        throw badRequest(result.reasons.joinToString("; ") { reason -> at?.let { "$it: $reason" } ?: reason } + ".")
    }
}

fun validName(name: String, field: String = "name", max: Int = 100): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || trimmed.length > max) throw badRequest("$field must be 1 to $max characters.")
    return trimmed
}

fun validTimestamp(value: Long, field: String): Long {
    if (value < 0) throw badRequest("$field must be epoch milliseconds, got $value.")
    return value
}
