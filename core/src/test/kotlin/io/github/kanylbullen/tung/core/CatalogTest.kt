package io.github.kanylbullen.tung.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
    fun everyCoreEntryHasOneToThreePrimaryAndAtMostFourSecondaryMuscles() {
        core.forEach { e ->
            assertTrue("${e.key}: ${e.primaryMuscles}", e.primaryMuscles.size in 1..3)
            assertTrue("${e.key}: ${e.secondaryMuscles}", e.secondaryMuscles.size <= 4)
            val all = e.primaryMuscles + e.secondaryMuscles
            assertEquals("${e.key} repeats a muscle", all.size, all.toSet().size)
        }
    }

    @Test
    fun everyMuscleInTheCatalogueIsInTheFixedList() {
        core.forEach { e ->
            (e.primaryMuscles + e.secondaryMuscles).forEach { assertNotNull("${e.key}: unknown muscle $it", Muscle.fromKey(it)) }
        }
        extended.forEach { e ->
            (e.primaryMuscles + e.secondaryMuscles).forEach { assertNotNull("${e.id}: unknown muscle $it", Muscle.fromKey(it)) }
        }
    }

    /** `muscleGroup` is the display level: the group of the first primary muscle, or fullbody for whole-body lifts. */
    @Test
    fun muscleGroupFollowsTheFirstPrimaryMuscle() {
        core.forEach { e ->
            val first = e.primaryMuscles.firstOrNull()?.let(Muscle::fromKey)
            assertNotNull(e.key, first)
            assertTrue("${e.key}: ${e.muscleGroup} but first primary ${first?.key}", first != null && muscleGroupMatches(e.muscleGroup, first))
        }
        extended.forEach { e ->
            val first = e.primaryMuscles.firstOrNull()?.let(Muscle::fromKey)
            assertNotNull("${e.id} has no primary muscle", first)
            assertTrue("${e.id}: ${e.muscleGroup} but first primary ${first?.key}", first != null && muscleGroupMatches(e.muscleGroup, first))
        }
    }

    @Test
    fun theIndexKnowsEveryCatalogueExercise() {
        val index = MuscleIndex.build(core, extended)
        core.forEach { e ->
            val byKey = index.musclesOf(e.key)
            assertNotNull(e.key, byKey)
            assertEquals(byKey, index.musclesOf(catalogExerciseId(e.key)))
            assertEquals(e.primaryMuscles, byKey?.primary?.map { it.key })
            assertEquals(e.secondaryMuscles, byKey?.secondary?.map { it.key })
        }
        extended.forEach { e ->
            assertNotNull(e.id, index.musclesOf(extendedExerciseId(e.id)))
        }
        assertEquals(
            listOf(Muscle.CHEST),
            index.musclesOf("bench_press")?.primary,
        )
        assertEquals(listOf(Muscle.HAMSTRINGS, Muscle.GLUTES), index.musclesOf("romanian_deadlift")?.primary)
    }

    /**
     * `instructions` is optional; where it is there it has English steps, none
     * of them blank (Swedish comes later and falls back to English). The
     * import writes them for every core entry with a free-exercise-db twin.
     */
    @Test
    fun coreInstructionsAreEnglishStepsWhereTheyExist() {
        val withSteps = core.filter { it.instructions.isNotEmpty() }
        assertTrue("only ${withSteps.size} core entries have instructions", withSteps.size >= 60)
        withSteps.forEach { e ->
            val en = e.instructions["en"]
            assertNotNull("${e.key} has instructions but no en", en)
            assertTrue("${e.key} has no steps", !en.isNullOrEmpty())
            e.instructions.forEach { (lang, steps) ->
                steps.forEach { assertTrue("${e.key}: a blank $lang step", it.isNotBlank()) }
            }
            assertEquals(en, e.instructionsIn("sv"))
        }
        assertEquals(emptyList<String>(), core.single { it.key == "burpee" }.instructionsIn("en"))
        assertTrue(core.single { it.key == "bench_press" }.instructionsIn("en").first().startsWith("Lie back on a flat bench"))
    }

    @Test
    fun instructionsAreOptionalWhenParsing() {
        val parsed = CoreCatalog.parse(
            """[{"key": "a", "name": {"en": "A"}, "muscleGroup": "chest", "equipment": "other"},
               {"key": "b", "name": {"en": "B"}, "muscleGroup": "chest", "equipment": "other", "instructions": {"en": ["One.", "Two."], "sv": ["Ett."]}}]""",
        )
        assertEquals(emptyMap<String, List<String>>(), parsed[0].instructions)
        assertEquals(listOf("One.", "Two."), parsed[1].instructionsIn("en"))
        assertEquals(listOf("Ett."), parsed[1].instructionsIn("sv"))
        assertEquals(listOf("One.", "Two."), parsed[1].instructionsIn("de"))
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
