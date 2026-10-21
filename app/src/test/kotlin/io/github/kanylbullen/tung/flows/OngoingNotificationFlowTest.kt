package io.github.kanylbullen.tung.flows

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.compose.ui.test.isDialog
import io.github.kanylbullen.tung.MainActivity
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.StartedBy
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.command.RoutinePlan
import io.github.kanylbullen.tung.core.command.RoutinePlanExercise
import io.github.kanylbullen.tung.core.command.id
import io.github.kanylbullen.tung.data.Workout
import io.github.kanylbullen.tung.data.R as DataR
import io.github.kanylbullen.tung.ongoing.OngoingWorkoutNotifier
import io.github.kanylbullen.tung.ongoing.OngoingWorkoutReceiver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNotificationManager
import org.robolectric.util.ReflectionHelpers

/**
 * The ongoing notification of a workout in progress (`data/.../ongoing/`):
 * it appears whoever started the workout, says where the workout is, logs a
 * set from its action without opening the app, opens the finish dialog from
 * "Finish", and goes away when the workout ends.
 */
class OngoingNotificationFlowTest : FlowTest() {

    private val manager: ShadowNotificationManager get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private val benchPress get() = str(R.string.ex_bench_press)

    private fun ongoing(): Notification? = manager.getNotification(OngoingWorkoutNotifier.NOTIFICATION_ID)

    private fun body(notification: Notification?): String? = notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun action(notification: Notification, title: String): Notification.Action =
        checkNotNull(notification.actions?.firstOrNull { it.title.toString() == title }) {
            "no action '$title' among ${notification.actions?.map { it.title }}"
        }

    @Before
    fun allowNotifications() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * A routine of three bench sets is started: "Workout in progress · Bench
     * press · set 1 of 3" appears, ongoing, with the elapsed time. Its "Log
     * set" ticks set 1 without opening anything and the text moves to set 2.
     * Its "Finish" opens the workout with the finish dialog; confirming it
     * removes the notification.
     */
    @Test
    fun theNotificationFollowsTheWorkoutLogsASetAndFinishes() {
        val workoutId = runBlocking {
            val routineId = app.container.commands.execute(
                Command.SaveRoutinePlan(
                    RoutinePlan("Push day", listOf(RoutinePlanExercise(ExerciseRef.ByKey("bench_press"), targetSets = 3, targetReps = 8))),
                ),
            ).id(CommandIds.ROUTINE_ID)
            app.container.commands.execute(Command.StartRoutine(checkNotNull(routineId))).id(CommandIds.WORKOUT_ID)
        }
        assertNotNull(workoutId)

        waitUntil { body(ongoing()) == str(DataR.string.ongoing_at_set, benchPress, 1, 3) }
        val shown = checkNotNull(ongoing())
        assertEquals(str(DataR.string.ongoing_title), shown.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals(OngoingWorkoutNotifier.CHANNEL_ID, shown.channelId)
        assertEquals(Notification.CATEGORY_WORKOUT, shown.category)
        assertTrue("ongoing", shown.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue("shows the elapsed time", shown.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertNull("started on this phone, so no 'started by'", shown.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            app.getSystemService(NotificationManager::class.java).getNotificationChannel(OngoingWorkoutNotifier.CHANNEL_ID).importance,
        )
        val open = shadowOf(shown.contentIntent).savedIntent
        assertEquals(workoutId, open.getStringExtra(MainActivity.EXTRA_OPEN_WORKOUT))

        // Log set: a broadcast to the receiver in data/, no activity.
        val logSet = action(shown, str(DataR.string.ongoing_log_set))
        assertEquals(
            OngoingWorkoutReceiver::class.java.name,
            shadowOf(logSet.actionIntent).savedIntent.component?.className,
        )
        logSet.actionIntent.send()
        waitUntil { count("SELECT COUNT(*) FROM set_entry WHERE completed = 1") == 1 }
        assertEquals(1, count("SELECT COUNT(*) FROM set_entry WHERE completed = 1 AND position = 0"))
        waitUntil { body(ongoing()) == str(DataR.string.ongoing_at_set, benchPress, 2, 3) }

        // Finish: opens the workout with the finish dialog; nothing is finished until it is confirmed.
        val finish = shadowOf(action(checkNotNull(ongoing()), str(DataR.string.ongoing_finish)).actionIntent).savedIntent
        assertEquals(workoutId, finish.getStringExtra(MainActivity.EXTRA_OPEN_WORKOUT))
        assertTrue(finish.getBooleanExtra(MainActivity.EXTRA_FINISH_WORKOUT, false))
        deliverToActivity(finish)
        waitFor(isDialog())
        screenshot("1-finish-dialog-from-the-notification")
        assertEquals(1, count("SELECT COUNT(*) FROM workout WHERE endedAt IS NULL"))
        clickInDialog(str(R.string.workout_finish))

        waitUntil { ongoing() == null }
        assertEquals(0, count("SELECT COUNT(*) FROM workout WHERE endedAt IS NULL"))
    }

    /**
     * Issue #64: a routine of one bench set. The notification's Log set ticks
     * it and adds nothing; the body reads "Bench press · all sets logged" and
     * the action is "Add set", which adds a copy of the set without opening
     * anything. The notification is back at Log set, and the stale Add set,
     * sent again, adds nothing.
     */
    @Test
    fun withEverySetLoggedTheActionIsAddSet() {
        runBlocking {
            val routineId = app.container.commands.execute(
                Command.SaveRoutinePlan(
                    RoutinePlan("Bench", listOf(RoutinePlanExercise(ExerciseRef.ByKey("bench_press"), targetSets = 1, targetReps = 8))),
                ),
            ).id(CommandIds.ROUTINE_ID)
            app.container.commands.execute(Command.StartRoutine(checkNotNull(routineId)))
        }
        waitUntil { body(ongoing()) == str(DataR.string.ongoing_at_set, benchPress, 1, 1) }
        action(checkNotNull(ongoing()), str(DataR.string.ongoing_log_set)).actionIntent.send()
        waitUntil { body(ongoing()) == str(DataR.string.ongoing_all_logged, benchPress) }
        assertEquals(1, count("SELECT COUNT(*) FROM set_entry"))
        val shown = checkNotNull(ongoing())
        assertEquals(
            listOf(str(DataR.string.ongoing_add_set), str(DataR.string.ongoing_finish)),
            shown.actions?.map { it.title.toString() },
        )

        val addSet = action(shown, str(DataR.string.ongoing_add_set)).actionIntent
        assertEquals(OngoingWorkoutReceiver::class.java.name, shadowOf(addSet).savedIntent.component?.className)
        addSet.send()
        waitUntil { count("SELECT COUNT(*) FROM set_entry") == 2 }
        assertEquals(1, count("SELECT COUNT(*) FROM set_entry WHERE completed = 0 AND position = 1 AND reps = 8"))
        waitUntil { body(ongoing()) == str(DataR.string.ongoing_at_set, benchPress, 2, 2) }
        assertNotNull(checkNotNull(ongoing()).actions?.firstOrNull { it.title.toString() == str(DataR.string.ongoing_log_set) })

        // Stale: a set is open again, so the old Add set does nothing.
        addSet.send()
        waitUntil { body(ongoing()) == str(DataR.string.ongoing_at_set, benchPress, 2, 2) }
        Thread.sleep(500)
        compose.waitForIdle()
        assertEquals(2, count("SELECT COUNT(*) FROM set_entry"))
    }

    /**
     * A workout an assistant started through the server (arrived by sync,
     * `startedBy = agent`) shows "Started by your assistant". With no sets
     * there is nothing to log: only "Finish". Discarding it removes the
     * notification.
     */
    @Test
    fun aWorkoutStartedByTheAssistantSaysSo() {
        val now = System.currentTimeMillis()
        runBlocking {
            db.workoutDao().insertWorkout(Workout(id = "w-agent", startedAt = now - 60_000, updatedAt = now, startedBy = StartedBy.AGENT))
        }
        waitUntil { ongoing() != null }
        val shown = checkNotNull(ongoing())
        assertEquals(str(DataR.string.ongoing_started_by_agent), shown.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
        assertEquals(plural(DataR.plurals.ongoing_sets_logged, 0, 0), body(shown))
        assertEquals(listOf(str(DataR.string.ongoing_finish)), shown.actions?.map { it.title.toString() })

        runBlocking { app.container.commands.execute(Command.FinishWorkout("w-agent")) }
        waitUntil { ongoing() == null }
    }

    /** As the system hands a notification's intent to the running activity (`singleTop`): `onNewIntent`. */
    private fun deliverToActivity(intent: Intent) {
        compose.runOnUiThread {
            ReflectionHelpers.callInstanceMethod<Unit>(
                compose.activity,
                "onNewIntent",
                ReflectionHelpers.ClassParameter.from(Intent::class.java, intent),
            )
        }
    }
}
