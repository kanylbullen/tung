package io.github.kanylbullen.tung.data

import io.github.kanylbullen.tung.core.CoreCatalog
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The seed list is generated from `catalog/core.json` into the data module, so
 * it checks it; the catalogue data itself is checked by `CatalogTest` in `core/`.
 * Gradle runs unit tests with `data/` as working directory.
 */
class SeedExercisesTest {

    private fun catalogFile(name: String): File =
        listOf(File("../catalog/$name"), File("catalog/$name")).firstOrNull { it.isFile }
            ?: error("catalog/$name not found from ${File(".").absolutePath}")

    @Test
    fun coreMatchesTheGeneratedSeedList() {
        val core = CoreCatalog.parse(catalogFile("core.json").readText())
        val seeds = SeedExercises.all.associateBy { it.key }
        assertEquals(core.map { it.key }.toSet(), seeds.keys)
        core.forEach { e ->
            val seed = seeds.getValue(e.key)
            assertEquals("${e.key}: the fallback name is English", e.name["en"].orEmpty(), seed.name)
            assertEquals(e.muscleGroup, seed.muscle.key)
            assertEquals(e.equipment, seed.equipment.key)
        }
    }
}
