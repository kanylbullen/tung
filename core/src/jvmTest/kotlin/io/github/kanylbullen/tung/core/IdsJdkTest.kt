package io.github.kanylbullen.tung.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** The ids against `java.util.UUID`, which only the JVM has; the rest is `IdsTest` in commonTest. */
class IdsJdkTest {

    private val uuidFormat = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    @Test
    fun catalogueIdIsAValidNameBasedUuid() {
        val id = catalogExerciseId("bench_press")
        assertTrue(id, uuidFormat.matches(id))
        val parsed = UUID.fromString(id)
        assertEquals(id, parsed.toString())
        assertEquals(3, parsed.version())
    }
}
