package io.github.kanylbullen.tung.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.catalogExerciseId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The progress reads (`WorkoutDao.observeProgressSets`): only ticked sets with
 * reps, only finished workouts, with the exercise and the workout's start.
 */
@RunWith(RobolectricTestRunner::class)
class ProgressSetsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = TungDatabase.buildInMemory(context)
    private var now = 1_000L
    private val workouts = WorkoutRepository(db, clock = { now })
    private val bench = catalogExerciseId("bench_press")
    private val squat = catalogExerciseId("back_squat")

    @After
    fun closeDatabase() = db.close()

    /** A workout at [startedAt] with [exercises] (id to sets of weight × reps × ticked); finished unless [finish] is false. */
    private fun workout(startedAt: Long, vararg exercises: Pair<String, List<Triple<Double, Int, Boolean>>>, finish: Boolean = true): String =
        runBlocking {
            now = startedAt
            val id = workouts.startOrResumeWorkout()
            for ((exerciseId, sets) in exercises) {
                val weId = workouts.addExercise(id, exerciseId, sets = sets.size)
                workouts.getSets(weId).sortedBy { it.position }.zip(sets).forEach { (entry, s) ->
                    workouts.updateWeight(entry.id, s.first)
                    workouts.updateReps(entry.id, s.second)
                    if (s.third) workouts.setCompleted(entry.id, true)
                }
            }
            if (finish) {
                now = startedAt + 60_000
                workouts.finishWorkout(id)
            }
            id
        }

    @Test
    fun onlyLoggedSetsOfFinishedWorkouts() = runBlocking {
        val first = workout(
            10_000,
            bench to listOf(Triple(80.0, 5, true), Triple(85.0, 3, false)),
            squat to listOf(Triple(100.0, 5, true)),
        )
        val second = workout(20_000, bench to listOf(Triple(82.5, 5, true)))
        workout(30_000, bench to listOf(Triple(200.0, 1, true)), finish = false)

        val all = workouts.observeProgressSets().first()
        assertEquals(
            listOf(
                Triple(bench, first, LoggedSet(80.0, 5)),
                Triple(squat, first, LoggedSet(100.0, 5)),
                Triple(bench, second, LoggedSet(82.5, 5)),
            ),
            all.map { Triple(it.exerciseId, it.workoutId, it.set) },
        )
        assertEquals(listOf(10_000L, 10_000L, 20_000L), all.map { it.startedAt })

        val benchOnly = workouts.observeProgressSets(bench).first()
        assertEquals(listOf(LoggedSet(80.0, 5), LoggedSet(82.5, 5)), benchOnly.map { it.set })
    }
}
