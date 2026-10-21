package io.github.kanylbullen.tung.core.catalog

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.ExtendedExercise
import io.github.kanylbullen.tung.core.ExtendedName
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.extendedExerciseId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class BrowseTest {

    private val order = browseNameOrder(Locale.ENGLISH)

    private fun lib(key: String, name: String, group: MuscleGroup, equipment: Equipment = Equipment.BARBELL, custom: Boolean = false) =
        LibraryExercise(catalogExerciseId(key), name, group.key, equipment.key, custom)

    private fun ext(id: String, name: String, group: MuscleGroup, equipment: Equipment = Equipment.DUMBBELL) =
        ExtendedExercise(id, ExtendedName(name), group.key, listOf("chest"), emptyList(), equipment.key, emptyList(), "free-exercise-db")

    private val library = listOf(
        lib("bench_press", "Bench press", MuscleGroup.CHEST),
        lib("back_squat", "Back squat", MuscleGroup.LEGS),
        lib("barbell_row", "Barbell row", MuscleGroup.BACK),
        LibraryExercise("custom-1", "Ändrad press", MuscleGroup.CHEST.key, Equipment.OTHER.key, isCustom = true),
        // An extended entry the user already picked: it is a library row now.
        LibraryExercise(extendedExerciseId("Zercher_Squats"), "Zercher Squats", MuscleGroup.LEGS.key, Equipment.BARBELL.key),
    )

    private val extended = listOf(
        ext("Zercher_Squats", "Zercher Squats", MuscleGroup.LEGS, Equipment.BARBELL),
        ext("Around_The_Worlds", "Around The Worlds", MuscleGroup.CHEST),
        ext("Barbell_Shrug", "Barbell Shrug", MuscleGroup.BACK, Equipment.BARBELL),
    )

    private val items = browseItems(library, extended)

    @Test
    fun theLibraryAndTheRestOfTheExtendedCatalogueEachOnce() {
        assertEquals(library.size + 2, items.size)
        val zercher = items.filter { it.name == "Zercher Squats" }
        assertEquals(1, zercher.size)
        assertEquals(ExercisePageRef.Library(extendedExerciseId("Zercher_Squats")), zercher.single().ref)
        assertEquals(ExercisePageRef.Extended("Barbell_Shrug"), items.single { it.name == "Barbell Shrug" }.ref)
        assertTrue(items.single { it.name == "Ändrad press" }.isCustom)
    }

    @Test
    fun sectionsFollowTheMuscleGroupOrderAndNamesAreSortedWithinThem() {
        val sections = browse(items, group = null, query = "", nameOrder = order)
        assertEquals(listOf(MuscleGroup.CHEST, MuscleGroup.BACK, MuscleGroup.LEGS), sections.map { it.group })
        // The collator puts "Ä" with "A", not after "Z".
        assertEquals(listOf("Ändrad press", "Around The Worlds", "Bench press"), sections[0].items.map { it.name })
        assertEquals(listOf("Barbell row", "Barbell Shrug"), sections[1].items.map { it.name })
        assertEquals(listOf("Back squat", "Zercher Squats"), sections[2].items.map { it.name })
    }

    @Test
    fun aGroupChipShowsOnlyThatGroup() {
        val sections = browse(items, group = MuscleGroup.BACK, query = "", nameOrder = order)
        assertEquals(listOf(MuscleGroup.BACK), sections.map { it.group })
        assertEquals(2, sections.single().items.size)
        assertEquals(emptyList<BrowseSection>(), browse(items, MuscleGroup.CORE, "", order))
    }

    @Test
    fun searchMatchesTheDisplayedNameAcrossBothWithoutDiacritics() {
        val sections = browse(items, group = null, query = "andrad", nameOrder = order)
        assertEquals(listOf("Ändrad press"), sections.flatMap { s -> s.items.map { it.name } })
        val barbell = browse(items, null, "BARBELL", order).flatMap { s -> s.items.map { it.ref } }
        assertEquals(listOf(ExercisePageRef.Library(catalogExerciseId("barbell_row")), ExercisePageRef.Extended("Barbell_Shrug")), barbell)
        // Chip and search together.
        assertEquals(emptyList<BrowseSection>(), browse(items, MuscleGroup.LEGS, "bench", order))
    }

    @Test
    fun sameNamesKeepAStableOrder() {
        val twins = listOf(
            BrowseItem(ExercisePageRef.Extended("b"), "Curl", MuscleGroup.BICEPS, Equipment.OTHER),
            BrowseItem(ExercisePageRef.Extended("a"), "Curl", MuscleGroup.BICEPS, Equipment.OTHER),
        )
        val sorted = browse(twins, null, "", order).single().items.map { it.ref.route }
        assertEquals(listOf("ext:a", "ext:b"), sorted)
        assertEquals(sorted, browse(twins.reversed(), null, "", order).single().items.map { it.ref.route })
    }

    @Test
    fun theChipsAreTheGroupsThatHaveExercises() {
        assertEquals(listOf(MuscleGroup.CHEST, MuscleGroup.BACK, MuscleGroup.LEGS), browseGroups(items))
        assertEquals(emptyList<MuscleGroup>(), browseGroups(emptyList()))
    }

    @Test
    fun pageRefsRoundTripThroughTheRoute() {
        val library = ExercisePageRef.Library(catalogExerciseId("bench_press"))
        val extended = ExercisePageRef.Extended("Zercher_Squats")
        assertEquals(library, ExercisePageRef.parse(library.route))
        assertEquals(extended, ExercisePageRef.parse(extended.route))
        assertEquals("ext:Zercher_Squats", extended.route)
        assertEquals(extendedExerciseId("Zercher_Squats"), extended.exerciseId)
        assertEquals(ExerciseRef.ById(library.exerciseId), library.toExerciseRef())
        assertEquals(ExerciseRef.Extended("Zercher_Squats"), extended.toExerciseRef())
        assertNull(ExercisePageRef.parse(""))
        assertNull(ExercisePageRef.parse("ext:"))
    }

    /** "My equipment" narrows the sections and the chips; it combines with the group and the search. */
    @Test
    fun theEquipmentFilterCombinesWithTheGroupAndTheSearch() {
        val dumbbells = setOf(Equipment.DUMBBELL)
        val sections = browse(items, group = null, query = "", nameOrder = order, available = dumbbells)
        assertEquals(listOf("Around The Worlds"), sections.flatMap { s -> s.items.map { it.name } })
        assertEquals(listOf(MuscleGroup.CHEST), browseGroups(items, dumbbells))
        assertEquals(emptyList<BrowseSection>(), browse(items, MuscleGroup.LEGS, "", order, dumbbells))
        assertEquals(emptyList<BrowseSection>(), browse(items, null, "bench", order, dumbbells))

        val barbell = setOf(Equipment.BARBELL)
        assertEquals(
            listOf("Back squat", "Zercher Squats"),
            browse(items, MuscleGroup.LEGS, "", order, barbell).single().items.map { it.name },
        )
        assertEquals(listOf("Zercher Squats"), browse(items, null, "zerch", order, barbell).single().items.map { it.name })
        // Everything is the same as no filter.
        assertEquals(browse(items, null, "", order), browse(items, null, "", order, EquipmentFilter.ALL))
        assertEquals(browseGroups(items), browseGroups(items, EquipmentFilter.ALL))
    }
}
