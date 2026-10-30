package io.github.kanylbullen.tung.flows

import android.app.AlarmManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.timer.RestTimerReceiver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

class LogSetFlowTest : FlowTest() {

    private val benchPress get() = str(R.string.ex_bench_press)
    private val countdown get() = hasContentDescription(str(R.string.rest_title))
    private val alarms: ShadowAlarmManager get() = shadowOf(app.getSystemService(AlarmManager::class.java))

    private fun logSet(weight: String, reps: String) = str(R.string.workout_log_set, benchPress, weight, reps)
    private fun addSet(weight: String, reps: String) = str(R.string.workout_sticky_add_set, benchPress, weight, reps)

    /** Seconds the countdown in the top bar says are left, read from its state description ("1:28 left"). */
    private fun restSecondsLeft(): Int {
        waitFor(countdown)
        val state = compose.onNode(countdown).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        val clock = checkNotNull(state).removeSuffix(str(R.string.rest_remaining, "").trim()).trim()
        val (m, s) = clock.split(':').map { it.toInt() }
        return m * 60 + s
    }

    /** The one rest alarm, as AlarmManager holds it: milliseconds from now until it goes off. */
    private fun alarmInMillis(): Long {
        val scheduled = alarms.scheduledAlarms
        assertEquals("one rest alarm", 1, scheduled.size)
        assertEquals(
            RestTimerReceiver::class.java.name,
            shadowOf(scheduled.single().operation).savedIntent.component?.className,
        )
        return scheduled.single().triggerAtMs - System.currentTimeMillis()
    }

    private fun activeSets(where: String): Int = count(
        "SELECT COUNT(*) FROM set_entry s JOIN workout_exercise we ON s.workoutExerciseId = we.id " +
            "JOIN workout w ON we.workoutId = w.id WHERE w.endedAt IS NULL AND $where",
    )

    /**
     * Someone who benched 80 × 8 last time adds Bench press (one set, as last
     * session) and taps "Log set · 80 × 8" at the bottom: set 1 is ticked, no
     * set is added (issue #64), a 1:30 rest counts down in the top bar with an
     * alarm set for when it ends, and the button now reads "Add set · Bench
     * press · 80 × 8". Tapping it adds set 2 at 80 × 8 as the current set, and
     * the button is "Log set" again.
     */
    @Test
    fun logSetTicksTheCurrentSetAndTheButtonOffersAddSet() {
        seedFinishedWorkout(System.currentTimeMillis() - 2 * DAY, bench(), weightKg = 80.0, reps = 8)
        startWorkoutFromHome()
        addExercise("bench", benchPress)
        waitForText(logSet("80", "8"))
        compose.onNode(countdown).assertDoesNotExist()
        screenshot("1-log-set-button")

        clickText(logSet("80", "8"))
        waitForDescription(str(R.string.workout_mark_undone, 1))
        waitFor(countdown)
        assertTrue(restSecondsLeft() in 85..90)
        waitForText(addSet("80", "8"))
        compose.onNode(hasText(logSet("80", "8"))).assertDoesNotExist()
        compose.onNode(hasContentDescription(str(R.string.workout_mark_done, 2))).assertDoesNotExist()
        screenshot("2-logged-add-set")

        assertEquals(1, activeSets("s.completed = 1 AND s.weightKg = 80.0 AND s.reps = 8 AND s.position = 0"))
        assertEquals(1, activeSets("1"))
        assertTrue(alarmInMillis() in 85_000L..90_000L)

        clickText(addSet("80", "8"))
        waitForDescription(str(R.string.workout_mark_done, 2))
        waitForText(logSet("80", "8"))
        assertShown(hasContentDescription(str(R.string.workout_mark_done, 2)))
        compose.onNode(hasText(addSet("80", "8"))).assertDoesNotExist()
        screenshot("3-added-set-is-current")
        assertEquals(1, activeSets("s.completed = 0 AND s.weightKg = 80.0 AND s.reps = 8 AND s.position = 1"))
        assertEquals(2, activeSets("1"))

        // The next tap logs the added set, and the button offers Add set again.
        clickText(logSet("80", "8"))
        waitForDescription(str(R.string.workout_mark_undone, 2))
        waitForText(addSet("80", "8"))
        assertEquals(2, activeSets("s.completed = 1"))
        assertEquals(2, activeSets("1"))
    }

    /**
     * Someone logs a set, the rest starts, the activity is recreated and the
     * countdown is still there; tapping it shows Skip and +30 s, and Skip ends
     * the rest and cancels its alarm.
     */
    @Test
    fun skipEndsTheRest() {
        seedFinishedWorkout(System.currentTimeMillis() - 2 * DAY, bench(), weightKg = 80.0, reps = 8)
        startWorkoutFromHome()
        addExercise("bench", benchPress)
        clickText(logSet("80", "8"))
        waitFor(countdown)

        compose.activityRule.scenario.recreate()
        waitFor(countdown)
        assertTrue(restSecondsLeft() in 80..90)

        compose.onNode(countdown).performClick()
        waitForText(str(R.string.rest_skip))
        // +30 s first: the countdown and the alarm both move half a minute.
        clickDescription(str(R.string.rest_add_description))
        waitUntil(5_000) { restSecondsLeft() > 90 }
        // The alarm follows the countdown once the previous rest's DataStore write is done.
        waitUntil(5_000) { runCatching { alarmInMillis() in 110_000L..120_000L }.getOrDefault(false) }
        screenshot("1-rest-controls")

        clickText(str(R.string.rest_skip))
        waitGone(countdown)
        compose.onNode(hasText(str(R.string.rest_skip))).assertDoesNotExist()
        waitUntil(15_000) { alarms.scheduledAlarms.isEmpty() }
        screenshot("2-skipped")
    }

    /**
     * A routine asks for three sets of bench press. Logging three times ticks
     * the three sets and adds no fourth. With every set ticked the button
     * offers Add set rather than Finish (which stays in the top bar).
     */
    @Test
    fun aRoutineExerciseIsNotPaddedPastItsTargetSets() {
        val source = seedWorkout(System.currentTimeMillis() - 3 * DAY, bench(), listOf(80.0 to 8, 80.0 to 8, 80.0 to 8))
        runBlocking {
            val routines = RoutineRepository(db)
            checkNotNull(routines.start(checkNotNull(routines.saveFromWorkout(source, "Push day"))))
        }
        clickText(str(R.string.home_resume_workout))
        waitForDescription(str(R.string.workout_mark_done, 3))
        screenshot("1-routine-workout")

        for (n in 1..3) {
            clickText(logSet("80", "8"))
            waitForDescription(str(R.string.workout_mark_undone, n))
        }
        waitForText(addSet("80", "8"))
        compose.onNode(hasText(logSet("80", "8"))).assertDoesNotExist()
        compose.onNode(hasContentDescription(str(R.string.workout_mark_done, 4))).assertDoesNotExist()
        assertShown(hasText(str(R.string.workout_finish)))
        screenshot("2-three-logged")

        assertEquals(3, activeSets("s.completed = 1"))
        assertEquals(3, activeSets("1"))
    }

    /**
     * Someone sets the rest for bench press to 1:00 from its menu. It is stored
     * on the exercise, it is still there after the activity is recreated, and
     * the next "Log set" starts a one-minute rest.
     */
    @Test
    fun theRestOfAnExerciseIsKeptAndUsed() {
        seedFinishedWorkout(System.currentTimeMillis() - 2 * DAY, bench(), weightKg = 80.0, reps = 8)
        startWorkoutFromHome()
        addExercise("bench", benchPress)

        clickDescription(str(R.string.workout_exercise_menu, benchPress))
        clickText(str(R.string.workout_rest_timer))
        waitForText(str(R.string.rest_between_sets, benchPress))
        waitForText("1:30")
        screenshot("1-rest-sheet")
        clickDescription(str(R.string.rest_shorter))
        waitForText("1:00")
        clickText(str(R.string.input_done))
        waitGone(hasContentDescription(str(R.string.rest_shorter)))

        waitUntil(5_000) { count("SELECT COUNT(*) FROM exercise WHERE id = '${bench()}' AND restSeconds = 60") == 1 }
        assertEquals(1, count("SELECT COUNT(*) FROM exercise WHERE id = '${bench()}' AND updatedAt > 0"))

        compose.activityRule.scenario.recreate()
        clickText(logSet("80", "8"))
        waitFor(countdown)
        assertTrue(restSecondsLeft() in 55..60)
        assertTrue(alarmInMillis() in 55_000L..60_000L)
        screenshot("2-one-minute-rest")

        // The sheet opens on the stored value next time.
        clickDescription(str(R.string.workout_exercise_menu, benchPress))
        clickText(str(R.string.workout_rest_timer))
        waitForText(str(R.string.rest_between_sets, benchPress))
        assertShown(hasText("1:00"))
    }

    /**
     * A first set with no reps yet: "Log set" asks for the reps instead of
     * logging a set that would be thrown away, and starts no rest.
     */
    @Test
    fun logSetWithoutRepsAsksForThem() {
        startWorkoutFromHome()
        addExercise("bench", benchPress)
        clickText(logSet("0", str(R.string.workout_reps_empty)))
        waitForDescription(str(R.string.input_field_reps))
        screenshot("1-reps-sheet")
        compose.onNode(countdown).assertDoesNotExist()
        assertEquals(0, activeSets("s.completed = 1"))
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    /**
     * The set the button shows lost its reps after the screen last read it (a
     * sync's write landing before the tap; here written behind Room's back, so
     * the screen still says 80 × 8). `log_set` refuses it, and the refusal
     * changes nothing: no tick, no rest, no alarm, and the set stays current,
     * now shown without reps, so the next tap asks for them as before.
     */
    @Test
    fun aRefusedLogSetStartsNoRestAndKeepsTheCurrentSet() {
        seedFinishedWorkout(System.currentTimeMillis() - 2 * DAY, bench(), weightKg = 80.0, reps = 8)
        startWorkoutFromHome()
        addExercise("bench", benchPress)
        waitForText(logSet("80", "8"))
        // Room's invalidation tracker looks only after a Room transaction, so the screen keeps showing 80 × 8.
        db.openHelper.writableDatabase.execSQL("UPDATE set_entry SET reps = 0 WHERE completed = 0")
        clickText(logSet("80", "8"))
        // The command's own transaction brought the change to the screen: the same set, without reps, still current.
        waitForText(logSet("80", str(R.string.workout_reps_empty)))
        screenshot("1-refused")
        compose.onNode(countdown).assertDoesNotExist()
        assertEquals(0, activeSets("s.completed = 1"))
        assertTrue(alarms.scheduledAlarms.isEmpty())
        compose.onNode(hasText(addSet("80", "8"))).assertDoesNotExist()
    }

    private fun seedWorkout(startedAt: Long, exerciseId: String, sets: List<Pair<Double, Int>>): String = runBlocking {
        var now = startedAt
        val repo = WorkoutRepository(db, clock = { now })
        val workoutId = repo.startOrResumeWorkout()
        val workoutExerciseId = repo.addExercise(workoutId, exerciseId, sets = 1)
        repeat(sets.size - 1) { repo.addSet(workoutExerciseId) }
        val ids = db.openHelper.readableDatabase
            .query("SELECT id FROM set_entry WHERE workoutExerciseId = ? ORDER BY position", arrayOf(workoutExerciseId))
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        ids.zip(sets).forEach { (id, set) ->
            repo.updateWeight(id, set.first)
            repo.updateReps(id, set.second)
            repo.setCompleted(id, true)
        }
        now = startedAt + 45 * 60_000L
        check(repo.finishWorkout(workoutId)) { "seeded workout was discarded" }
        workoutId
    }

    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }
}
