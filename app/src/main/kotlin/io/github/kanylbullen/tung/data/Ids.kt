package io.github.kanylbullen.tung.data

import java.util.UUID

/**
 * Primary keys are UUID strings generated on the device, so rows created on
 * different devices (or imported from an export) never collide.
 */
fun newId(): String = UUID.randomUUID().toString()

/**
 * A catalogue exercise's id, derived from its [nameKey] (name-based UUID, v3),
 * so the same catalogue row has the same id on every device.
 */
fun catalogExerciseId(nameKey: String): String =
    UUID.nameUUIDFromBytes("tung:exercise:$nameKey".toByteArray(Charsets.UTF_8)).toString()
