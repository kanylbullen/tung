package io.github.kanylbullen.tung.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class IdsTest {

    private val uuidFormat = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    @Test
    fun sameCatalogueKeyGivesTheSameIdEveryTime() {
        assertEquals(catalogExerciseId("bench_press"), catalogExerciseId("bench_press"))
    }

    @Test
    fun differentCatalogueKeysGiveDifferentIds() {
        assertNotEquals(catalogExerciseId("bench_press"), catalogExerciseId("back_squat"))
    }

    @Test
    fun catalogueIdIsAValidNameBasedUuid() {
        val id = catalogExerciseId("bench_press")
        assertTrue(id, uuidFormat.matches(id))
        val parsed = UUID.fromString(id)
        assertEquals(id, parsed.toString())
        assertEquals(3, parsed.version())
    }

    @Test
    fun newIdGivesDistinctValidUuids() {
        val a = newId()
        val b = newId()
        assertNotEquals(a, b)
        assertTrue(a, uuidFormat.matches(a))
    }
}
