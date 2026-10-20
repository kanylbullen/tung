package io.github.kanylbullen.tung.health

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import io.github.kanylbullen.tung.core.health.HealthSamples
import io.github.kanylbullen.tung.core.health.HealthSession
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Health Connect for the data and flow tests, which run where there is none.
 * Grants what is asked for when [grantOnRequest] (the permission sheet answers
 * at once, through [ActivityResultContract.getSynchronousResult], so no
 * activity is started), records every session written in [written], and
 * answers every read with [samples] for the interval asked for.
 */
class FakeHealthConnect(
    @Volatile var availability: HealthAvailability = HealthAvailability.AVAILABLE,
    @Volatile var grantOnRequest: Boolean = true,
) : HealthConnectGateway {

    /** Granted permissions; a test can grant or revoke by hand. */
    val granted: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** Every session written, in order. */
    val written: MutableList<HealthSession> = CopyOnWriteArrayList()

    /** How many times [readVitals] ran. */
    val reads = AtomicInteger()

    /** What a read of [startMillis]..[endMillis] returns; nothing by default. */
    @Volatile var samples: (startMillis: Long, endMillis: Long) -> HealthSamples = { _, _ -> HealthSamples() }

    /** How many times [availability] was asked: the recorder asks first, so a test can wait for it to have run. */
    val availabilityChecks = AtomicInteger()

    /** How many times the permission sheet was shown. */
    val permissionRequests = AtomicInteger()

    override fun availability(): HealthAvailability {
        availabilityChecks.incrementAndGet()
        return availability
    }

    override fun permissions(access: HealthAccess): Set<String> = when (access) {
        HealthAccess.WRITE -> setOf(WRITE_EXERCISE)
        HealthAccess.READ -> setOf(READ_HEART_RATE, READ_ACTIVE_CALORIES)
    }

    override suspend fun hasPermissions(access: HealthAccess): Boolean =
        availability == HealthAvailability.AVAILABLE && granted.containsAll(permissions(access))

    override fun permissionRequest(): ActivityResultContract<Set<String>, Set<String>> =
        object : ActivityResultContract<Set<String>, Set<String>>() {
            override fun getSynchronousResult(context: Context, input: Set<String>): SynchronousResult<Set<String>> {
                permissionRequests.incrementAndGet()
                if (grantOnRequest) granted += input
                return SynchronousResult(granted.intersect(input))
            }

            override fun createIntent(context: Context, input: Set<String>): Intent = error("answered synchronously")

            override fun parseResult(resultCode: Int, intent: Intent?): Set<String> = emptySet()
        }

    override suspend fun writeSession(session: HealthSession): String {
        written += session
        return "hc-session-${written.size}"
    }

    override suspend fun readVitals(startMillis: Long, endMillis: Long): HealthSamples {
        reads.incrementAndGet()
        return samples(startMillis, endMillis)
    }

    companion object {
        const val WRITE_EXERCISE = "android.permission.health.WRITE_EXERCISE"
        const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
        const val READ_ACTIVE_CALORIES = "android.permission.health.READ_ACTIVE_CALORIES_BURNED"
    }
}
