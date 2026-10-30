package io.github.kanylbullen.tung.sync

import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.github.kanylbullen.tung.data.Workout
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Health Connect columns (issue #45) over sync: carried both ways, and a null from a peer never erases them. */
class SyncMappingHealthTest {

    private val local = Workout(
        id = "w", startedAt = 1_000, endedAt = 2_000, updatedAt = 2_000, dirty = false,
        avgHeartRate = 120, maxHeartRate = 165, activeCalories = 310, healthConnectId = "hc-1",
    )

    @Test
    fun theColumnsArePushed() {
        val row = local.toSync()
        assertEquals(120, row.avgHeartRate)
        assertEquals(165, row.maxHeartRate)
        assertEquals(310, row.activeCalories)
        assertEquals("hc-1", row.healthConnectId)
    }

    @Test
    fun aNullFromAPeerKeepsTheStoredValues() {
        // A newer row from a peer that knows nothing of Health Connect (the watch, an older client, the server's commands).
        val row = SyncWorkout("w", 1_000, 2_000, "Edited", null, null, 3_000)
        val merged = row.toEntity(local)!!
        assertEquals("Edited", merged.note)
        assertEquals(3_000L, merged.updatedAt)
        assertEquals(120, merged.avgHeartRate)
        assertEquals(165, merged.maxHeartRate)
        assertEquals(310, merged.activeCalories)
        assertEquals("hc-1", merged.healthConnectId)
    }

    @Test
    fun aValueFromAPeerIsTaken() {
        val row = SyncWorkout(
            "w", 1_000, 2_000, null, null, null, 3_000,
            avgHeartRate = 130, maxHeartRate = 170, activeCalories = 400, healthConnectId = "hc-2",
        )
        val merged = row.toEntity(Workout(id = "w", startedAt = 1_000, updatedAt = 2_000))!!
        assertEquals(130, merged.avgHeartRate)
        assertEquals(170, merged.maxHeartRate)
        assertEquals(400, merged.activeCalories)
        assertEquals("hc-2", merged.healthConnectId)
        // And with no local row at all.
        assertEquals(130, row.toEntity(null)!!.avgHeartRate)
    }
}
