package io.github.kanylbullen.tung.health

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.github.kanylbullen.tung.core.health.CaloriesRecord
import io.github.kanylbullen.tung.core.health.HealthSamples
import io.github.kanylbullen.tung.core.health.HealthSegmentType
import io.github.kanylbullen.tung.core.health.HealthSession
import io.github.kanylbullen.tung.core.health.HeartRateSample
import java.time.Instant
import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * The real [HealthConnectGateway] over `androidx.health.connect:connect-client`.
 * Built by the phone's `TungApp` only; the watch has no Health Connect and
 * passes none. The client is created on first use, never when the provider
 * is unavailable.
 */
class AndroidHealthConnectGateway(context: Context) : HealthConnectGateway {

    private val context: Context = context.applicationContext ?: context
    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(this.context) }

    override fun availability(): HealthAvailability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.NEEDS_UPDATE
        else -> HealthAvailability.UNAVAILABLE
    }

    override fun permissions(access: HealthAccess): Set<String> = healthPermissions(access)

    override suspend fun hasPermissions(access: HealthAccess): Boolean {
        if (availability() != HealthAvailability.AVAILABLE) return false
        return client.permissionController.getGrantedPermissions().containsAll(permissions(access))
    }

    override fun permissionRequest(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    override suspend fun writeSession(session: HealthSession): String {
        val start = Instant.ofEpochMilli(session.startMillis)
        val end = Instant.ofEpochMilli(session.endMillis)
        val zone = ZoneId.systemDefault().rules
        val record = ExerciseSessionRecord(
            startTime = start,
            startZoneOffset = zone.getOffset(start),
            endTime = end,
            endZoneOffset = zone.getOffset(end),
            // Logged live on this phone; the client record id makes a repeated insert replace the first.
            metadata = Metadata.activelyRecorded(Device(type = Device.TYPE_PHONE), session.clientRecordId, 0L),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            title = session.title,
            segments = session.segments.map {
                ExerciseSegment(Instant.ofEpochMilli(it.startMillis), Instant.ofEpochMilli(it.endMillis), it.type.toHealthConnect(), it.repetitions)
            },
        )
        return client.insertRecords(listOf(record)).recordIdsList.single()
    }

    override suspend fun readVitals(startMillis: Long, endMillis: Long): HealthSamples {
        val range = TimeRangeFilter.between(Instant.ofEpochMilli(startMillis), Instant.ofEpochMilli(endMillis))
        val heartRate = readAll(HeartRateRecord::class, range).flatMap { record ->
            record.samples.map { HeartRateSample(it.time.toEpochMilli(), it.beatsPerMinute) }
        }
        val calories = readAll(ActiveCaloriesBurnedRecord::class, range).map {
            CaloriesRecord(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.energy.inKilocalories, it.metadata.dataOrigin.packageName)
        }
        return HealthSamples(heartRate, calories)
    }

    private suspend fun <T : Record> readAll(type: KClass<T>, range: TimeRangeFilter): List<T> {
        val records = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(type, range, pageToken = pageToken))
            records += response.records
            pageToken = response.pageToken
        } while (pageToken != null && records.size < MAX_RECORDS)
        return records
    }

    private companion object {
        /** A workout of a few hours has a few thousand samples at most; past this something is wrong. */
        const val MAX_RECORDS = 10_000
    }
}

/** The permissions each opt-in asks for; the same list is declared in the phone's manifest. */
fun healthPermissions(access: HealthAccess): Set<String> = when (access) {
    HealthAccess.WRITE -> setOf(HealthPermission.getWritePermission(ExerciseSessionRecord::class))
    HealthAccess.READ -> setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
    )
}

/** Core's segment type as the library's constant. Exhaustive, so a new [HealthSegmentType] does not compile until it is mapped here. */
fun HealthSegmentType.toHealthConnect(): Int = when (this) {
    HealthSegmentType.ARM_CURL -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL
    HealthSegmentType.BACK_EXTENSION -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_BACK_EXTENSION
    HealthSegmentType.BARBELL_SHOULDER_PRESS -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_BARBELL_SHOULDER_PRESS
    HealthSegmentType.BENCH_PRESS -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS
    HealthSegmentType.BURPEE -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_BURPEE
    HealthSegmentType.CRUNCH -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_CRUNCH
    HealthSegmentType.DEADLIFT -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_DEADLIFT
    HealthSegmentType.DUMBBELL_ROW -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_ROW
    HealthSegmentType.DUMBBELL_TRICEPS_EXTENSION_TWO_ARM -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_TRICEPS_EXTENSION_TWO_ARM
    HealthSegmentType.FRONT_RAISE -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_FRONT_RAISE
    HealthSegmentType.HIP_THRUST -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_HIP_THRUST
    HealthSegmentType.KETTLEBELL_SWING -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_KETTLEBELL_SWING
    HealthSegmentType.LATERAL_RAISE -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_LATERAL_RAISE
    HealthSegmentType.LAT_PULL_DOWN -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_LAT_PULL_DOWN
    HealthSegmentType.LEG_CURL -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_CURL
    HealthSegmentType.LEG_EXTENSION -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_EXTENSION
    HealthSegmentType.LEG_PRESS -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_PRESS
    HealthSegmentType.LEG_RAISE -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_RAISE
    HealthSegmentType.LUNGE -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_LUNGE
    HealthSegmentType.PLANK -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_PLANK
    HealthSegmentType.PULL_UP -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_PULL_UP
    HealthSegmentType.SHOULDER_PRESS -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_SHOULDER_PRESS
    HealthSegmentType.SIT_UP -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_SIT_UP
    HealthSegmentType.SQUAT -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT
    HealthSegmentType.WEIGHTLIFTING -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_WEIGHTLIFTING
}
