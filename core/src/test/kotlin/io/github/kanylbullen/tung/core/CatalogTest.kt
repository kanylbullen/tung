package io.github.kanylbullen.tung.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Checks the open catalogue files in `catalog/` as data. Gradle runs unit tests
 * with the module directory (`core/`) as working directory, so the files are at
 * `../catalog/`. That the seed list in the app matches `core.json` is checked
 * by `SeedExercisesTest` in the app, which owns the generated list.
 */
class CatalogTest {

    private fun catalogFile(name: String): File =
        listOf(File("../catalog/$name"), File("catalog/$name")).firstOrNull { it.isFile }
            ?: error("catalog/$name not found from ${File(".").absolutePath}")

    private val core: List<CoreExercise> by lazy { CoreCatalog.parse(catalogFile("core.json").readText()) }

    private val CoreExercise.en: String get() = name["en"].orEmpty()
    private val CoreExercise.sv: String get() = name["sv"].orEmpty()

    private val extended: List<ExtendedExercise> by lazy {
        catalogFile("extended.json").inputStream().use(ExtendedCatalog::parse)
    }

    private val muscleKeys = MuscleGroup.entries.map { it.key }.toSet()
    private val equipmentKeys = Equipment.entries.map { it.key }.toSet()

    /** Same normalisation as catalog/tools/import_free_exercise_db.py. */
    private fun normalize(name: String): String =
        name.lowercase(Locale.ROOT).replace('-', ' ').split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    @Test
    fun coreHasSeventyEntriesWithUniqueKeys() {
        assertEquals(70, core.size)
        assertEquals(core.size, core.map { it.key }.toSet().size)
    }

    @Test
    fun coreIsSortedByKey() {
        assertEquals(core.map { it.key }.sorted(), core.map { it.key })
    }

    /** English is the default locale and the fallback name, so it must be complete. */
    @Test
    fun everyCoreEntryHasAnEnglishName() {
        core.forEach { e -> assertTrue("${e.key} has no en name", e.en.isNotBlank()) }
    }

    /** Swedish is the shipped translation and is kept complete as well. */
    @Test
    fun everyCoreEntryHasASwedishName() {
        core.forEach { e -> assertTrue("${e.key} has no sv name", e.sv.isNotBlank()) }
    }

    @Test
    fun allMuscleGroupsAndEquipmentAreKnownValues() {
        core.forEach { e ->
            assertTrue("${e.key}: ${e.muscleGroup}", e.muscleGroup in muscleKeys)
            assertTrue("${e.key}: ${e.equipment}", e.equipment in equipmentKeys)
        }
        extended.forEach { e ->
            assertTrue("${e.id}: ${e.muscleGroup}", e.muscleGroup in muscleKeys)
            assertTrue("${e.id}: ${e.equipment}", e.equipment in equipmentKeys)
        }
    }

    @Test
    fun extendedHasUniqueIdsAndNoEmptyNames() {
        assertTrue(extended.isNotEmpty())
        assertEquals(extended.size, extended.map { it.id }.toSet().size)
        extended.forEach { e ->
            assertTrue("empty id", e.id.isNotBlank())
            assertTrue("${e.id} has an empty name", e.name.en.isNotBlank())
        }
    }

    @Test
    fun extendedDoesNotRepeatACoreExercise() {
        val coreNames = core.map { normalize(it.en) }.toSet()
        val clashes = extended.filter { normalize(it.name.en) in coreNames }.map { it.id }
        assertEquals(emptyList<String>(), clashes)
    }

    @Test
    fun extendedIdsAreStableAndDistinctFromCoreIds() {
        val id = extendedExerciseId("Zercher_Squats")
        assertEquals(id, extendedExerciseId("Zercher_Squats"))
        val coreIds = core.map { catalogExerciseId(it.key) }.toSet()
        val extendedIds = extended.map { extendedExerciseId(it.id) }
        assertEquals(extendedIds.size, extendedIds.toSet().size)
        assertTrue(extendedIds.none { it in coreIds })
        // A core key and an extended id spelled the same still get different ids.
        assertNotEquals(catalogExerciseId("deadlift"), extendedExerciseId("deadlift"))
    }

    @Test
    fun searchNeedsTwoCharactersAndSkipsWhatIsAlreadyInRoom() {
        assertEquals(emptyList<ExtendedExercise>(), ExtendedCatalog.search(extended, emptySet(), "z"))
        val zercher = ExtendedCatalog.search(extended, emptySet(), "zerch")
        assertTrue(zercher.isNotEmpty())
        assertTrue(zercher.all { it.name.en.contains("Zercher", ignoreCase = true) })
        val picked = zercher.first()
        val afterPick = ExtendedCatalog.search(extended, setOf(extendedExerciseId(picked.id)), "zerch")
        assertFalse(picked in afterPick)
        assertEquals(zercher.size - 1, afterPick.size)
    }
}
