package io.github.kanylbullen.tung.flows

import io.github.kanylbullen.tung.ui.resources.*
import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.PAUSE_AFTER_MILLIS
import io.github.kanylbullen.tung.ongoing.OngoingWorkoutNotifier
import io.github.kanylbullen.tung.ongoing.OngoingWorkoutReceiver
import io.github.kanylbullen.tung.ui.common.clockText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import io.github.kanylbullen.tung.data.R as DataR

/**
 * A workout with no input for an hour is paused (issue #261): its clock
 * stops at the last input and says so, Finish proposes the time up to that
 * input plus a few minutes, any input resumes it, and the ongoing
 * notification stops counting. Nothing is stored: the pause is read off the
 * rows' `updatedAt`, which these tests move back by hand.
 */
class PausedWorkoutFlowTest : FlowTest() {

    private val benchPress get() = str(R.string.ex_bench_press)
    private val minute = 60_000L

    @Before
    fun allowNotifications() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Bench press logged, then the workout forgotten: it started two and a
     * half hours ago and its last input was two hours ago. Opened again, the
     * clock reads 30:00 and "Paused"; Finish offers 35 minutes and ends it
     * there, not now.
     */
    @Test
    fun aPausedWorkoutStopsItsClockAndFinishesAtItsLastInput() {
        val (start, last) = forgottenWorkout()

        waitForDescription(str(Res.string.workout_paused_description, clockText(last - start)))
        screenshot("1-paused")

        clickText(str(Res.string.workout_finish))
        waitFor(hasText(str(Res.string.duration_minutes, 35)) and hasAnyAncestor(isDialog()))
        screenshot("2-finish")
        clickInDialog(str(Res.string.workout_finish_confirm))
        waitForText(str(Res.string.summary_done))
        waitUntil { count("SELECT COUNT(*) FROM workout WHERE startedAt = $start AND endedAt = ${last + 5 * minute}") == 1 }
    }

    /** The same forgotten workout: un-ticking the set is input, so the clock runs again from the start. */
    @Test
    fun anInputResumesAPausedWorkout() {
        val (start, last) = forgottenWorkout()
        waitForDescription(str(Res.string.workout_paused_description, clockText(last - start)))

        clickDescription(str(Res.string.workout_mark_undone, 1))
        waitFor(hasContentDescription(str(Res.string.workout_elapsed_description, "2:30"), substring = true))
        compose.onNode(hasContentDescription(str(Res.string.workout_paused_description, clockText(last - start)))).assertDoesNotExist()
        screenshot("1-resumed")
    }

    /**
     * The notification while the workout runs carries the elapsed time and an
     * alarm for the moment it would pause; once paused it says "Workout
     * paused", shows the last input's time and no longer counts.
     */
    @Test
    fun theNotificationStopsCountingWhenPaused() {
        val manager = shadowOf(app.getSystemService(NotificationManager::class.java))
        fun ongoing(): Notification? = manager.getNotification(OngoingWorkoutNotifier.NOTIFICATION_ID)
        fun title(): String? = ongoing()?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

        seedFinishedWorkout(SEEDED, bench(), weightKg = 80.0, reps = 8)
        startWorkoutFromHome()
        addExercise("bench", benchPress)
        clickText(str(Res.string.workout_log_set, benchPress, "80", "8"))
        waitForDescription(str(Res.string.workout_mark_undone, 1))
        waitUntil { title() == str(DataR.string.ongoing_title) || title() == str(DataR.string.ongoing_rest_title) }
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        waitUntil {
            alarms.scheduledAlarms.any { alarm ->
                shadowOf(alarm.operation).savedIntent.action == OngoingWorkoutReceiver.ACTION_REFRESH &&
                    alarm.triggerAtTime > System.currentTimeMillis() + PAUSE_AFTER_MILLIS - minute
            }
        }

        // A running rest would show first; this one is over.
        runBlocking { app.container.restTimer.set(workoutId = "", timer = null) }
        waitUntil { title() == str(DataR.string.ongoing_title) }
        val (_, last) = ageWorkout()
        // The rows moved behind Room's back; the alarm's refresh reads them again.
        runBlocking { app.container.ongoingWorkout.refresh() }
        waitUntil { title() == str(DataR.string.ongoing_paused_title) }
        val shown = checkNotNull(ongoing())
        assertFalse("no longer counts", shown.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals("shows the last input", last, shown.`when`)
        assertTrue("still ongoing, a reminder to finish", shown.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    /** A workout with Bench press 80 × 8 logged, then moved back: started 150 minutes ago, last input 120 minutes ago; reopened from Home. */
    private fun forgottenWorkout(): Pair<Long, Long> {
        seedFinishedWorkout(SEEDED, bench(), weightKg = 80.0, reps = 8)
        startWorkoutFromHome()
        addExercise("bench", benchPress)
        clickText(str(Res.string.workout_log_set, benchPress, "80", "8"))
        waitForDescription(str(Res.string.workout_mark_undone, 1))
        val times = ageWorkout()
        // Room sees a change only after its own transaction: a new screen reads the rows afresh.
        clickDescription(str(Res.string.action_back))
        clickText(str(Res.string.home_resume_workout))
        return times
    }

    /** Moves the workout in progress back: started 150 minutes ago, every row last written 120 minutes ago. */
    private fun ageWorkout(): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val start = now - 150 * minute
        val last = now - 120 * minute
        db.openHelper.writableDatabase.apply {
            execSQL("UPDATE workout SET startedAt = $start, updatedAt = $last WHERE endedAt IS NULL")
            execSQL("UPDATE workout_exercise SET updatedAt = $last WHERE workoutId IN (SELECT id FROM workout WHERE endedAt IS NULL)")
            execSQL(
                "UPDATE set_entry SET updatedAt = $last WHERE workoutExerciseId IN " +
                    "(SELECT we.id FROM workout_exercise we JOIN workout w ON we.workoutId = w.id WHERE w.endedAt IS NULL)",
            )
        }
        return start to last
    }

    private companion object {
        /** A finished workout long before the test's days, so Bench press has a last session. */
        const val SEEDED = 1_760_090_400_000L // 2025-10-10, 10:00 UTC
    }
}
