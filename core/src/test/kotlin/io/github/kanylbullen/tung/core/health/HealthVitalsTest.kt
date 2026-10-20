package io.github.kanylbullen.tung.core.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthVitalsTest {

    private val start = 1_000_000L
    private val end = start + 3_600_000L

    @Test
    fun heartRateIsTheRoundedMeanAndTheMaximumOfTheSamplesInside() {
        val samples = HealthSamples(
            heartRate = listOf(
                HeartRateSample(start - 1, 200), // before the workout
                HeartRateSample(start, 100),
                HeartRateSample(start + 60_000, 121),
                HeartRateSample(end, 150),
                HeartRateSample(end + 1, 190), // after it
            ),
        )
        val vitals = aggregateVitals(samples, start, end)
        assertEquals(124, vitals.avgHeartRate) // (100 + 121 + 150) / 3 = 123.67
        assertEquals(150, vitals.maxHeartRate)
        assertEquals(null, vitals.activeCalories)
    }

    @Test
    fun implausibleHeartRatesAreDropped() {
        val samples = HealthSamples(heartRate = listOf(HeartRateSample(start, 0), HeartRateSample(start + 1, 400), HeartRateSample(start + 2, 90)))
        val vitals = aggregateVitals(samples, start, end)
        assertEquals(90, vitals.avgHeartRate)
        assertEquals(90, vitals.maxHeartRate)
    }

    @Test
    fun caloriesCountByTheShareOfEachRecordInsideTheInterval() {
        val samples = HealthSamples(
            calories = listOf(
                CaloriesRecord(start, start + 1_800_000, 150.0, "watch"),
                // Half of it lies after the workout.
                CaloriesRecord(end - 600_000, end + 600_000, 100.0, "watch"),
                // Entirely before.
                CaloriesRecord(start - 600_000, start, 80.0, "watch"),
            ),
        )
        assertEquals(200, aggregateVitals(samples, start, end).activeCalories)
    }

    @Test
    fun theSameBurnFromTwoAppsIsNotCountedTwice() {
        val samples = HealthSamples(
            calories = listOf(
                CaloriesRecord(start, end, 310.0, "com.samsung.health"),
                CaloriesRecord(start, end, 290.0, "com.google.android.apps.fitness"),
            ),
        )
        assertEquals(310, aggregateVitals(samples, start, end).activeCalories)
    }

    @Test
    fun nothingReadIsEmpty() {
        val vitals = aggregateVitals(HealthSamples(), start, end)
        assertTrue(vitals.isEmpty)
    }
}
