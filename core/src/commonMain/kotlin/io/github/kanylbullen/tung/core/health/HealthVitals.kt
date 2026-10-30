package io.github.kanylbullen.tung.core.health

import kotlin.math.roundToInt

/** One heart-rate sample from Health Connect (`HeartRateRecord.Sample`). */
data class HeartRateSample(val timeMillis: Long, val beatsPerMinute: Long)

/** One `ActiveCaloriesBurnedRecord`: [kilocalories] over its interval, written by the app [source] (its package name). */
data class CaloriesRecord(val startMillis: Long, val endMillis: Long, val kilocalories: Double, val source: String)

/** What Health Connect has for a workout's interval, as read; [aggregateVitals] makes the workout's columns of it. */
data class HealthSamples(
    val heartRate: List<HeartRateSample> = emptyList(),
    val calories: List<CaloriesRecord> = emptyList(),
)

/** The values stored on a workout (`avgHeartRate`, `maxHeartRate`, `activeCalories`); each null when nothing was there. */
data class HealthVitals(val avgHeartRate: Int?, val maxHeartRate: Int?, val activeCalories: Int?) {
    val isEmpty: Boolean get() = avgHeartRate == null && maxHeartRate == null && activeCalories == null
}

/** Beats per minute outside this range are sensor noise, not a heart rate; they are dropped. */
const val MIN_HEART_RATE = 20
const val MAX_HEART_RATE = 300

/** The most active kilocalories a single workout can carry; the server refuses more. */
const val MAX_ACTIVE_CALORIES = 20_000

/**
 * The workout's vitals from what Health Connect had for
 * [[startMillis], [endMillis]]:
 *
 * - **Heart rate:** every sample inside the interval with a plausible value
 *   ([MIN_HEART_RATE]..[MAX_HEART_RATE]); the average is the mean of the
 *   samples, rounded, and the maximum the largest.
 * - **Calories:** a record that only partly overlaps the interval counts by
 *   the share of its time inside it. Several apps (a watch's own app and its
 *   companion, say) often write the same burn, so the totals are summed per
 *   [CaloriesRecord.source] and the largest source wins rather than adding
 *   them up. Rounded to whole kilocalories; null when nothing overlapped.
 */
fun aggregateVitals(samples: HealthSamples, startMillis: Long, endMillis: Long): HealthVitals {
    val beats = samples.heartRate
        .filter { it.timeMillis in startMillis..endMillis && it.beatsPerMinute in MIN_HEART_RATE..MAX_HEART_RATE }
        .map { it.beatsPerMinute }
    val avg = if (beats.isEmpty()) null else (beats.sum().toDouble() / beats.size).roundToInt()
    val max = beats.maxOrNull()?.toInt()

    val perSource = samples.calories.mapNotNull { record ->
        val overlap = minOf(record.endMillis, endMillis) - maxOf(record.startMillis, startMillis)
        val length = record.endMillis - record.startMillis
        when {
            record.kilocalories <= 0.0 || overlap < 0 -> null
            length <= 0 -> if (record.startMillis in startMillis..endMillis) record.source to record.kilocalories else null
            overlap == 0L -> null
            else -> record.source to record.kilocalories * overlap / length
        }
    }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
    val calories = perSource.values.maxOrNull()?.roundToInt()?.coerceAtMost(MAX_ACTIVE_CALORIES)

    return HealthVitals(avg, max, calories)
}
