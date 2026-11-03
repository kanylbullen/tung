package io.github.kanylbullen.tung.core.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HealthConnectSegmentsTest {

    @Test
    fun everySegmentTypeIsUsed() {
        val unused = HealthSegmentType.entries.toSet() - HEALTH_CONNECT_SEGMENTS.values.toSet()
        assertTrue(unused.isEmpty(), "segment types no key maps to: $unused")
    }

    @Test
    fun theObviousLiftsAreMapped() {
        assertEquals(HealthSegmentType.BENCH_PRESS, healthSegmentFor("bench_press"))
        assertEquals(HealthSegmentType.SQUAT, healthSegmentFor("back_squat"))
        assertEquals(HealthSegmentType.DEADLIFT, healthSegmentFor("deadlift"))
        assertEquals(HealthSegmentType.LAT_PULL_DOWN, healthSegmentFor("lat_pulldown"))
        assertEquals(HealthSegmentType.BARBELL_SHOULDER_PRESS, healthSegmentFor("overhead_press"))
    }

    @Test
    fun customAndUnmatchedExercisesHaveNoSegment() {
        assertNull(healthSegmentFor(null))
        assertNull(healthSegmentFor("cable_fly"))
        assertNull(healthSegmentFor("no_such_key"))
    }

    @Test
    fun theSessionSplitsItsIntervalEvenlyAndSkipsUnmatchedSets() {
        val session = planHealthSession(
            workoutId = "w1",
            startMillis = 0,
            endMillis = 3_000_000,
            title = "Push day",
            sets = listOf(HealthSet("bench_press", 8), HealthSet("cable_fly", 12), HealthSet("bench_press", 6)),
        )
        assertEquals("tung:workout:w1", session.clientRecordId)
        assertEquals("Push day", session.title)
        assertEquals(0L, session.startMillis)
        assertEquals(3_000_000L, session.endMillis)
        assertEquals(
            listOf(
                HealthSegment(0, 1_000_000, HealthSegmentType.BENCH_PRESS, 8),
                HealthSegment(2_000_000, 3_000_000, HealthSegmentType.BENCH_PRESS, 6),
            ),
            session.segments,
        )
    }

    @Test
    fun segmentsNeverOverlapAndEndInsideTheSession() {
        val sets = List(7) { HealthSet("deadlift", 5) }
        val session = planHealthSession("w", 1_000, 1_000 + 3_600_001, "Workout", sets)
        session.segments.zipWithNext().forEach { (a, b) -> assertTrue(a.endMillis <= b.startMillis, "$a overlaps $b") }
        session.segments.forEach { assertTrue(it.startMillis < it.endMillis, "$it") }
        assertEquals(session.endMillis, session.segments.last().endMillis)
    }

    @Test
    fun aTooShortWorkoutIsStretchedToOneSecondPerSet() {
        val session = planHealthSession("w", 5_000, 5_000, "Workout", listOf(HealthSet("squat_typo", 5), HealthSet("back_squat", 5)))
        assertEquals(7_000L, session.endMillis)
        assertEquals(listOf(HealthSegment(6_000, 7_000, HealthSegmentType.SQUAT, 5)), session.segments)
    }
}
