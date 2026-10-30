package io.github.kanylbullen.tung.core.catalog

import io.github.kanylbullen.tung.core.Equipment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EquipmentFilterTest {

    /** Every key reads back as its value; an unknown or missing key (a newer client's, an older row's) is `other`. */
    @Test
    fun equipmentKeysRoundTripAndUnknownKeysAreOther() {
        Equipment.entries.forEach { assertEquals(it, Equipment.fromKey(it.key)) }
        assertEquals(Equipment.entries.size, Equipment.entries.map { it.key }.toSet().size)
        assertEquals(Equipment.OTHER, Equipment.fromKey("sandbag"))
        assertEquals(Equipment.OTHER, Equipment.fromKey(null))
        assertEquals(
            listOf("barbell", "ez_bar", "dumbbell", "kettlebell", "machine", "cable", "band", "bodyweight", "medicine_ball", "exercise_ball", "other"),
            Equipment.entries.map { it.key },
        )
    }

    @Test
    fun passesIsMembership() {
        assertTrue(passes(Equipment.BODYWEIGHT, EquipmentFilter.BODYWEIGHT_ONLY))
        assertFalse(passes(Equipment.BARBELL, EquipmentFilter.BODYWEIGHT_ONLY))
        // Unticking bodyweight hides bodyweight exercises too.
        assertFalse(passes(Equipment.BODYWEIGHT, setOf(Equipment.DUMBBELL)))
        assertTrue(passes("kettlebell", setOf(Equipment.KETTLEBELL)))
        assertTrue(passes("sandbag", setOf(Equipment.OTHER)))
        assertFalse(passes("sandbag", setOf(Equipment.BARBELL)))
        Equipment.entries.forEach { assertTrue(passes(it, EquipmentFilter.ALL)) }
    }

    private data class Row(val id: String, val equipment: Equipment)

    private val rows = listOf(Row("a", Equipment.BARBELL), Row("b", Equipment.BODYWEIGHT), Row("c", Equipment.CABLE), Row("d", Equipment.BAND))

    @Test
    fun filterKeepsOrderAndTheAllowList() {
        val bodyweight = filterByEquipment(rows, EquipmentFilter.BODYWEIGHT_ONLY, id = { it.id }, equipment = { it.equipment })
        assertEquals(listOf("b"), bodyweight.map { it.id })
        // An exercise already in the workout always shows, in its place.
        val withCurrent = filterByEquipment(rows, EquipmentFilter.BODYWEIGHT_ONLY, setOf("c"), { it.id }, { it.equipment })
        assertEquals(listOf("b", "c"), withCurrent.map { it.id })
        // Nothing narrowed: the same list.
        assertSame(rows, filterByEquipment(rows, EquipmentFilter.ALL, id = { it.id }, equipment = { it.equipment }))
    }

    @Test
    fun keysAreStoredInEquipmentOrderAndUnknownOnesAreDropped() {
        assertEquals(listOf("dumbbell", "bodyweight"), EquipmentFilter.toKeys(setOf(Equipment.BODYWEIGHT, Equipment.DUMBBELL)))
        assertEquals(setOf(Equipment.BAND, Equipment.EZ_BAR), EquipmentFilter.fromKeys(listOf("band", "sandbag", "ez_bar")))
        assertEquals(EquipmentFilter.ALL, EquipmentFilter.fromKeys(EquipmentFilter.toKeys(EquipmentFilter.ALL)))
        assertFalse(EquipmentFilter.isNarrowed(EquipmentFilter.ALL))
        assertTrue(EquipmentFilter.isNarrowed(EquipmentFilter.BODYWEIGHT_ONLY))
    }
}
