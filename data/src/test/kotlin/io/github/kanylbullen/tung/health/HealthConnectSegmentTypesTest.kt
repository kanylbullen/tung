package io.github.kanylbullen.tung.health

import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import io.github.kanylbullen.tung.core.health.HealthSegmentType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneOffset

/**
 * Health Connect refuses a segment type that does not belong to the session's
 * exercise type when the record is built; every type core maps a catalogue
 * key to must be one a strength-training session accepts.
 */
@RunWith(RobolectricTestRunner::class)
class HealthConnectSegmentTypesTest {

    @Test
    fun everyMappedTypeIsAcceptedInAStrengthTrainingSession() {
        val start = Instant.ofEpochMilli(1_760_090_400_000L)
        val segments = HealthSegmentType.entries.mapIndexed { i, type ->
            ExerciseSegment(start.plusSeconds(i * 60L), start.plusSeconds(i * 60L + 30), type.toHealthConnect(), 5)
        }
        val record = ExerciseSessionRecord(
            startTime = start,
            startZoneOffset = ZoneOffset.UTC,
            endTime = start.plusSeconds(HealthSegmentType.entries.size * 60L),
            endZoneOffset = ZoneOffset.UTC,
            metadata = Metadata.activelyRecorded(Device(type = Device.TYPE_PHONE), "tung:workout:test", 0L),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            title = "Workout",
            segments = segments,
        )
        assertEquals(HealthSegmentType.entries.size, record.segments.size)
        assertEquals(HealthSegmentType.entries.size, HealthSegmentType.entries.map { it.toHealthConnect() }.toSet().size)
    }
}
