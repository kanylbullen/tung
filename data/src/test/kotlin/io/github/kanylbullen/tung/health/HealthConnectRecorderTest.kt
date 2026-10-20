package io.github.kanylbullen.tung.health

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.health.CaloriesRecord
import io.github.kanylbullen.tung.core.health.HealthSamples
import io.github.kanylbullen.tung.core.health.HealthSegmentType
import io.github.kanylbullen.tung.core.health.HeartRateSample
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.TungDatabase
import io.github.kanylbullen.tung.data.WorkoutRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class HealthConnectRecorderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = TungDatabase.buildInMemory(context)
    private var now = START
    private val workouts = WorkoutRepository(db, clock = { now })
    private val routines = RoutineRepository(db, clock = { now })
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings = HealthConnectSettings(
        PreferenceDataStoreFactory.create(scope = storeScope) { File(context.filesDir, "hc-${UUID.randomUUID()}.preferences_pb") },
    )
    private val gateway = FakeHealthConnect()
    private val recorder = HealthConnectRecorder(gateway, settings, workouts, routines) { "Workout" }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    private fun count(sql: String): Int = db.openHelper.readableDatabase.query(sql).use { c -> c.moveToFirst(); c.getInt(0) }

    /** Bench press 80 × 8 and a cable fly 20 × 12, finished 45 minutes after [START]. */
    private suspend fun finishedWorkout(): String {
        now = START
        val workoutId = workouts.startOrResumeWorkout()
        for ((key, reps) in listOf("bench_press" to 8, "cable_fly" to 12)) {
            val workoutExerciseId = workouts.addExercise(workoutId, catalogExerciseId(key))
            val setId = workouts.getSets(workoutExerciseId).single().id
            workouts.updateReps(setId, reps)
            workouts.setCompleted(setId, true)
        }
        now = START + 45 * 60_000L
        check(workouts.finishWorkout(workoutId))
        return workoutId
    }

    private suspend fun optIn(access: HealthAccess) {
        gateway.granted += gateway.permissions(access)
        settings.setEnabled(access, true)
    }

    @Test
    fun aFinishedWorkoutIsWrittenOnceAndItsIdStoredDirty() = runBlocking {
        optIn(HealthAccess.WRITE)
        val workoutId = finishedWorkout()
        db.openHelper.writableDatabase.execSQL("UPDATE workout SET dirty = 0")
        now += 1_000

        recorder.onWorkoutFinished(workoutId)
        recorder.onWorkoutFinished(workoutId)
        // Concurrent calls too: the second sees the stored id.
        listOf(async { recorder.onWorkoutFinished(workoutId) }, async { recorder.onWorkoutFinished(workoutId) }).awaitAll()

        val session = gateway.written.single()
        assertEquals("tung:workout:$workoutId", session.clientRecordId)
        assertEquals("Workout", session.title)
        assertEquals(START, session.startMillis)
        assertEquals(START + 45 * 60_000L, session.endMillis)
        // Bench press is a segment over the first half; the cable fly has no Health Connect type.
        assertEquals(listOf(HealthSegmentType.BENCH_PRESS), session.segments.map { it.type })
        assertEquals(8, session.segments.single().repetitions)

        val workout = workouts.getWorkout(workoutId)!!
        assertEquals("hc-session-1", workout.healthConnectId)
        assertEquals(now, workout.updatedAt)
        assertTrue(workout.dirty)
    }

    @Test
    fun aWorkoutThatAlreadyHasASessionIsNotWrittenAgain() = runBlocking {
        optIn(HealthAccess.WRITE)
        val workoutId = finishedWorkout()
        // Written by another phone and synced here.
        assertTrue(workouts.setHealthConnectId(workoutId, "from-the-other-phone"))
        assertEquals(false, workouts.setHealthConnectId(workoutId, "second"))

        recorder.onWorkoutFinished(workoutId)

        assertTrue(gateway.written.isEmpty())
        assertEquals("from-the-other-phone", workouts.getWorkout(workoutId)!!.healthConnectId)
    }

    @Test
    fun theRoutineNameIsTheTitle() = runBlocking {
        optIn(HealthAccess.WRITE)
        val source = finishedWorkout()
        val routineId = routines.saveFromWorkout(source, "Push day")!!
        now = START + DAY
        val workoutId = routines.start(routineId)!!
        workouts.getExercisesWithSets(workoutId).flatMap { it.sets }.forEach { workouts.setCompleted(it.id, true) }
        now += 30 * 60_000L
        check(workouts.finishWorkout(workoutId))

        recorder.onWorkoutFinished(workoutId)

        assertEquals("Push day", gateway.written.single().title)
    }

    @Test
    fun vitalsAreReadAggregatedAndStored() = runBlocking {
        optIn(HealthAccess.READ)
        val workoutId = finishedWorkout()
        gateway.samples = HealthSamples(
            heartRate = listOf(HeartRateSample(START + 60_000, 110), HeartRateSample(START + 120_000, 150)),
            calories = listOf(CaloriesRecord(START, START + 45 * 60_000L, 250.4, "watch")),
        )

        recorder.onWorkoutFinished(workoutId)

        val workout = workouts.getWorkout(workoutId)!!
        assertEquals(130, workout.avgHeartRate)
        assertEquals(150, workout.maxHeartRate)
        assertEquals(250, workout.activeCalories)
        // Reading is not writing.
        assertTrue(gateway.written.isEmpty())
        assertNull(workout.healthConnectId)
    }

    @Test
    fun nothingHappensWithoutTheOptIns() = runBlocking {
        gateway.granted += gateway.permissions(HealthAccess.WRITE) + gateway.permissions(HealthAccess.READ)
        val workoutId = finishedWorkout()
        gateway.samples = HealthSamples(heartRate = listOf(HeartRateSample(START + 60_000, 110)))

        recorder.onWorkoutFinished(workoutId)

        assertTrue(gateway.written.isEmpty())
        assertEquals(0, gateway.reads.get())
        assertNull(workouts.getWorkout(workoutId)!!.avgHeartRate)
    }

    @Test
    fun nothingHappensWhenAPermissionWasRevoked() = runBlocking {
        settings.setEnabled(HealthAccess.WRITE, true)
        settings.setEnabled(HealthAccess.READ, true)
        val workoutId = finishedWorkout()

        recorder.onWorkoutFinished(workoutId)

        assertTrue(gateway.written.isEmpty())
        assertEquals(0, gateway.reads.get())
        assertEquals(0, count("SELECT COUNT(*) FROM workout WHERE healthConnectId IS NOT NULL"))
    }

    @Test
    fun nothingHappensWhenHealthConnectIsUnavailable() = runBlocking {
        optIn(HealthAccess.WRITE)
        optIn(HealthAccess.READ)
        gateway.availability = HealthAvailability.NEEDS_UPDATE
        val workoutId = finishedWorkout()

        recorder.onWorkoutFinished(workoutId)

        assertTrue(gateway.written.isEmpty())
        assertEquals(0, gateway.reads.get())
    }

    private companion object {
        const val START = 1_760_090_400_000L
        const val DAY = 24 * 60 * 60_000L
    }
}
