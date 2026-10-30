package io.github.kanylbullen.tung.health

import androidx.activity.result.contract.ActivityResultContract
import io.github.kanylbullen.tung.core.health.HealthSamples
import io.github.kanylbullen.tung.core.health.HealthSession

/** The two things the user can opt in to, each with its own permissions and its own switch in Settings. */
enum class HealthAccess {
    /** Write finished workouts as strength-training sessions (`WRITE_EXERCISE`). */
    WRITE,

    /** Read heart rate and active calories for a finished workout (`READ_HEART_RATE`, `READ_ACTIVE_CALORIES_BURNED`). */
    READ,
}

/** Whether Health Connect can be used on this phone. */
enum class HealthAvailability {
    AVAILABLE,

    /** The provider is installed but too old, or missing where it is an app (Android 13 and lower). */
    NEEDS_UPDATE,

    /** Not on this device at all (Android below 9, a watch, a phone without Health Connect). */
    UNAVAILABLE,
}

/**
 * Health Connect as Tung uses it (CLAUDE.md, "Health Connect"), behind an
 * interface so the flow and data tests run against [FakeHealthConnect][io.github.kanylbullen.tung.health.FakeHealthConnect]
 * (the `data-fixtures` module): the devserver and Robolectric have no Health Connect.
 * [AndroidHealthConnectGateway] is the real one, on the phone only.
 */
interface HealthConnectGateway {
    fun availability(): HealthAvailability

    /** The Health Connect permissions [access] needs. */
    fun permissions(access: HealthAccess): Set<String>

    /** Whether every permission of [access] is granted. False when Health Connect is not available. */
    suspend fun hasPermissions(access: HealthAccess): Boolean

    /**
     * The standard permission sheet: input the permissions to ask for, output
     * the ones granted. The real one is `PermissionController.createRequestPermissionResultContract()`.
     */
    fun permissionRequest(): ActivityResultContract<Set<String>, Set<String>>

    /** Writes one exercise session of type strength training and returns its record id. */
    suspend fun writeSession(session: HealthSession): String

    /** Heart-rate samples and active-calories records overlapping [startMillis]..[endMillis], unaggregated. */
    suspend fun readVitals(startMillis: Long, endMillis: Long): HealthSamples
}
