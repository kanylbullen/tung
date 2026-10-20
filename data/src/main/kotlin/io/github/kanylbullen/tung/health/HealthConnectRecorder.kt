package io.github.kanylbullen.tung.health

import android.util.Log
import io.github.kanylbullen.tung.core.health.HealthSet
import io.github.kanylbullen.tung.core.health.aggregateVitals
import io.github.kanylbullen.tung.core.health.planHealthSession
import io.github.kanylbullen.tung.core.isLogged
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.Workout
import io.github.kanylbullen.tung.data.WorkoutRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * What happens in Health Connect when a workout is finished on the phone
 * (`AppCommandHandler`'s result path, through `TungContainer`):
 *
 * - **Write**, when opted in and permitted: one strength-training session
 *   ([planHealthSession]), and its record id stored on the workout through
 *   [WorkoutRepository.setHealthConnectId]. **Written once:** never for a
 *   workout that already has a `healthConnectId` (from this phone or, synced,
 *   from another), the id is stored only where none is, the calls are
 *   serialised, and the session's client record id makes a repeated insert
 *   replace rather than add.
 * - **Read**, opted in separately: heart rate and active calories over the
 *   workout, aggregated by [aggregateVitals] and stored with
 *   [WorkoutRepository.setVitals].
 *
 * A failure (a permission revoked meanwhile, the provider gone) is logged and
 * leaves the workout as it was; finishing never depends on Health Connect.
 */
class HealthConnectRecorder(
    private val gateway: HealthConnectGateway,
    private val settings: HealthConnectSettings,
    private val workouts: WorkoutRepository,
    private val routines: RoutineRepository,
    /** The session's title when the workout has no routine: "Workout", in the phone's language. */
    private val defaultTitle: () -> String,
) {
    private val mutex = Mutex()

    suspend fun onWorkoutFinished(workoutId: String) = mutex.withLock {
        if (gateway.availability() != HealthAvailability.AVAILABLE) return@withLock
        if (settings.enabled(HealthAccess.WRITE).first()) attempt("write") { write(workoutId) }
        if (settings.enabled(HealthAccess.READ).first()) attempt("read") { read(workoutId) }
    }

    private suspend fun write(workoutId: String) {
        val workout = finished(workoutId) ?: return
        if (workout.healthConnectId != null || !gateway.hasPermissions(HealthAccess.WRITE)) return
        val sets = workouts.getExercisesWithSets(workoutId)
            .sortedBy { it.workoutExercise.position }
            .flatMap { exercise ->
                exercise.sortedSets.filter { isLogged(it.completed, it.reps) }.map { HealthSet(exercise.exercise.nameKey, it.reps) }
            }
        val title = workout.routineId?.let { routines.observeRoutine(it).first()?.name } ?: defaultTitle()
        val session = planHealthSession(workoutId, workout.startedAt, workout.endedAt ?: return, title, sets)
        workouts.setHealthConnectId(workoutId, gateway.writeSession(session))
    }

    private suspend fun read(workoutId: String) {
        val workout = finished(workoutId) ?: return
        if (!gateway.hasPermissions(HealthAccess.READ)) return
        val end = workout.endedAt ?: return
        val vitals = aggregateVitals(gateway.readVitals(workout.startedAt, end), workout.startedAt, end)
        workouts.setVitals(workoutId, vitals.avgHeartRate, vitals.maxHeartRate, vitals.activeCalories)
    }

    private suspend fun finished(workoutId: String): Workout? = workouts.getWorkout(workoutId)?.takeIf { it.endedAt != null }

    private suspend fun attempt(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Health Connect $what failed", e)
        }
    }

    private companion object {
        const val TAG = "TungHealthConnect"
    }
}
