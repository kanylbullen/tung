package io.github.kanylbullen.tung.core

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Primary keys are UUID strings generated on the device, so rows created on
 * different devices (or imported from an export) never collide.
 */
@OptIn(ExperimentalUuidApi::class)
fun newId(): String = Uuid.random().toString()

/**
 * A catalogue exercise's id, derived from its [nameKey] (name-based UUID, v3),
 * so the same catalogue row has the same id on every device.
 */
fun catalogExerciseId(nameKey: String): String = nameBasedUuid("tung:exercise:$nameKey")

/**
 * The id an exercise from the extended catalogue (`catalog/extended.json`) gets
 * when it is first picked: derived from its free-exercise-db id, so it is the
 * same on every device and never collides with a core key.
 */
fun extendedExerciseId(sourceId: String): String = catalogExerciseId("ext:$sourceId")

/**
 * A version 3 (name-based, MD5) UUID of [name]'s UTF-8 bytes, formatted as
 * `java.util.UUID.nameUUIDFromBytes` formats it, which is what every stored
 * catalogue and import id was made with. Pure Kotlin, so every platform
 * derives the same id from the same name.
 */
fun nameBasedUuid(name: String): String {
    val bytes = md5(name.encodeToByteArray())
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x30).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = bytes.toHex()
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}
