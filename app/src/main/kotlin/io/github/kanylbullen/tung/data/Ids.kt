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

/**
 * The id an exercise from the extended catalogue (`catalog/extended.json`) gets
 * when it is first picked: derived from its free-exercise-db id, so it is the
 * same on every device and never collides with a core key.
 */
fun extendedExerciseId(sourceId: String): String = catalogExerciseId("ext:$sourceId")
