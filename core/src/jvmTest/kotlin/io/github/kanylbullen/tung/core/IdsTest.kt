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

    /**
     * The values `java.util.UUID.nameUUIDFromBytes` made for these names, which is
     * what every installed phone and the server store today; a platform whose
     * implementation drifts (the iOS target one day) would duplicate every catalogue
     * row, so the numbers are pinned here rather than compared with the JDK at run time.
     */
    @Test
    fun idsAreTheOnesTheJdkMade() {
        assertEquals("b557a0f5-3876-3bcb-a43e-3f61594dcf1d", catalogExerciseId("bench_press"))
        assertEquals("ac36bc65-86e1-34dc-a78e-5c6fe43f6425", catalogExerciseId("back_squat"))
        assertEquals("a0987c7b-89d4-34ee-bc20-f9e88f11f377", extendedExerciseId("Alternate_Incline_Dumbbell_Curl"))
        // Multi-byte UTF-8 (two- and three-byte sequences) hashes as the JDK's getBytes(UTF_8) did.
        assertEquals("a2335dea-bae1-3d8d-b56f-9b979a900208", nameBasedUuid("tung:exercise:bänkpress å ö €"))
    }

    @Test
    fun newIdGivesDistinctValidUuids() {
        val a = newId()
        val b = newId()
        assertNotEquals(a, b)
        assertTrue(a, uuidFormat.matches(a))
    }
}
