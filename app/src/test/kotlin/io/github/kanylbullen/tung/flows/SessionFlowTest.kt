package io.github.kanylbullen.tung.flows

import io.github.kanylbullen.tung.ui.resources.*
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performTextReplacement
import io.github.kanylbullen.tung.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.Shadows.shadowOf
import android.provider.Settings
import org.robolectric.shadows.ShadowToast

class SessionFlowTest : FlowTest() {

    private val benchPress get() = str(R.string.ex_bench_press)

    /**
     * A first-time user sees the empty home screen, starts a workout, adds Bench
     * press, finds its first set waiting at 0 kg and no reps, types 80 kg and 8
     * reps in the big number sheet, ticks the set, finishes, and lands on a
     * summary that says one set and 640 kg.
     */
    @Test
    fun firstWorkoutFromEmptyHomeToSummary() {
        waitForText(str(Res.string.home_empty))
        screenshot("1-home-empty")

        startWorkoutFromHome()
        screenshot("2-workout-empty")
        addExercise("bench", benchPress)

        // The first set is there already; with no history it starts at 0 kg and no reps.
        waitForDescription(str(Res.string.workout_edit_weight, "0"))
        waitForDescription(str(Res.string.workout_edit_reps, str(Res.string.workout_reps_empty)))
        screenshot("3-workout-first-set")

        clickDescription(str(Res.string.workout_edit_weight, "0"))
        waitForDescription(str(Res.string.input_field_weight))
        screenshot("4-weight-sheet")
        compose.onNode(hasContentDescription(str(Res.string.input_field_weight)))
            .performTextReplacement("80")
        clickText(str(Res.string.input_done))
        waitForDescription(str(Res.string.workout_edit_weight, "80"))

        enterInSheet(str(Res.string.workout_edit_reps, str(Res.string.workout_reps_empty)), str(Res.string.input_field_reps), "8", str(Res.string.workout_edit_reps, "8"))
        waitForDescription(str(Res.string.workout_edit_reps, "8"))

        clickDescription(str(Res.string.workout_mark_done, 1))
        waitForDescription(str(Res.string.workout_mark_undone, 1))
        screenshot("5-set-ticked")

        finishWorkout(screenshotStep = "6-finish-dialog")

        waitForText(str(Res.string.summary_title))
        assertShown(hasText("1") and hasText(str(Res.string.stat_sets)))
        assertShown(hasText("640") and hasText(str(Res.string.stat_volume)))
        assertShown(hasText(str(Res.string.set_value, "80", 8)))
        screenshot("7-summary")

        assertEquals(1, count("SELECT COUNT(*) FROM workout"))
        assertEquals(1, count("SELECT COUNT(*) FROM workout WHERE endedAt IS NOT NULL"))
        assertEquals(1, count("SELECT COUNT(*) FROM workout_exercise"))
        assertEquals(1, count("SELECT COUNT(*) FROM set_entry"))
        assertEquals(1, count("SELECT COUNT(*) FROM set_entry WHERE completed = 1 AND weightKg = 80.0 AND reps = 8"))
    }

    /**
     * Someone who benched 80 × 8 last time adds Bench press to a new workout and
     * sees "Last: 80 × 8" under the first set, which is already filled in with
     * 80 kg and 8 reps.
     */
    @Test
    fun secondWorkoutShowsLastTimeAndPrefills() {
        seedFinishedWorkout(startedAt = System.currentTimeMillis() - 2 * DAY, exerciseId = bench(), weightKg = 80.0, reps = 8)

        // The seeded workout shows on the home screen instead of the empty state.
        waitForText(str(Res.string.home_recent))

        startWorkoutFromHome()
        addExercise("bench", benchPress)

        waitForText(str(Res.string.workout_previous, "80", 8))
        waitForDescription(str(Res.string.workout_edit_weight, "80"))
        waitForDescription(str(Res.string.workout_edit_reps, "8"))
        screenshot("1-last-time")

        // Prefilled, not ticked: the new set is a suggestion until the user confirms it.
        assertShown(hasContentDescription(str(Res.string.workout_mark_done, 1)))
        assertEquals(
            1,
            count(
                "SELECT COUNT(*) FROM set_entry s JOIN workout_exercise we ON s.workoutExerciseId = we.id " +
                    "JOIN workout w ON we.workoutId = w.id " +
                    "WHERE w.endedAt IS NULL AND s.weightKg = 80.0 AND s.reps = 8 AND s.completed = 0",
            ),
        )
    }

    /**
     * Issue #215: the first rest that starts without exact alarms asks once
     * to allow them; "Allow" opens the system's page, and the next rest asks
     * nothing.
     */
    @Test
    fun theFirstRestAsksOnceToAllowExactAlarms() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        seedFinishedWorkout(startedAt = System.currentTimeMillis() - 2 * DAY, exerciseId = bench(), weightKg = 80.0, reps = 8)
        waitForText(str(Res.string.home_recent))
        startWorkoutFromHome()
        addExercise("bench", benchPress)
        waitForDescription(str(Res.string.workout_mark_done, 1))

        clickDescription(str(Res.string.workout_mark_done, 1))
        waitFor(isDialog())
        assertShown(hasText(str(Res.string.settings_exact_alarms_title)))
        screenshot("1-exact-alarms-asked")
        clickInDialog(str(Res.string.settings_exact_alarms_allow))
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, shadowOf(app).nextStartedActivity?.action)

        // Asked once: the flag is spent, so no later rest asks again.
        waitUntil { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        assertFalse(runBlocking { app.container.restTimer.firstTimeAskingForExactAlarms() })
    }

    /**
     * Someone starts a workout, adds an exercise, ticks nothing and finishes:
     * the dialog warns that nothing will be saved, a toast confirms it, and the
     * home screen is empty again.
     */
    @Test
    fun finishingWithNothingTickedDiscardsTheWorkout() {
        startWorkoutFromHome()
        addExercise("bench", benchPress)

        clickText(str(Res.string.workout_finish))
        waitFor(isDialog())
        assertShown(hasText(str(Res.string.workout_finish_body_empty)))
        screenshot("1-finish-empty-dialog")
        clickInDialog(str(Res.string.workout_finish_confirm))

        waitForText(str(Res.string.home_empty))
        assertShown(hasText(str(Res.string.home_start_workout)))
        screenshot("2-home-empty-again")

        assertEquals(str(Res.string.workout_discarded), ShadowToast.getTextOfLatestToast())
        assertEquals(0, count("SELECT COUNT(*) FROM workout"))
        assertEquals(0, count("SELECT COUNT(*) FROM workout_exercise"))
        assertEquals(0, count("SELECT COUNT(*) FROM set_entry"))
    }

    /**
     * Someone leaves a workout in progress with the back arrow, and the home
     * screen offers "Resume workout" instead of "Start workout" — also after the
     * activity is recreated — and tapping it brings back the same workout.
     */
    @Test
    fun resumeAWorkoutInProgress() {
        startWorkoutFromHome()
        addExercise("bench", benchPress)
        val workoutId = activeWorkoutId()

        clickDescription(str(Res.string.action_back))
        waitForText(str(Res.string.home_resume_workout))
        compose.onNode(hasText(str(Res.string.home_start_workout))).assertDoesNotExist()
        screenshot("1-home-resume")

        compose.activityRule.scenario.recreate()
        waitForText(str(Res.string.home_resume_workout))

        clickText(str(Res.string.home_resume_workout))
        waitForText(benchPress)
        waitForDescription(str(Res.string.workout_mark_done, 1))
        screenshot("2-resumed")

        assertEquals(1, count("SELECT COUNT(*) FROM workout"))
        assertEquals(workoutId, activeWorkoutId())
    }

    /**
     * Someone starts their very first workout and goes back to Home: it offers
     * "Resume workout" and how long the workout has been going, and does not
     * also claim there are no workouts yet.
     */
    @Test
    fun homeWithTheFirstWorkoutInProgressIsNotEmpty() {
        waitForText(str(Res.string.home_empty))
        startWorkoutFromHome()

        clickDescription(str(Res.string.action_back))
        waitForText(str(Res.string.home_resume_workout))
        compose.onNode(hasText(str(Res.string.home_empty))).assertDoesNotExist()
        compose.onNode(hasText(str(Res.string.home_recent))).assertDoesNotExist()
        screenshot("1-home-first-in-progress")
    }

    private fun activeWorkoutId(): String =
        db.openHelper.readableDatabase.query("SELECT id FROM workout WHERE endedAt IS NULL")
            .use { c -> check(c.count == 1) { "expected one active workout, found ${c.count}" }; c.moveToFirst(); c.getString(0) }

    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }

    /**
     * The finish dialog says what goes (review 2026-10-29): with every set
     * ticked "Every set is logged.", with one of two ticked "1 set that is not
     * ticked will be removed.", and the finished workout keeps only the ticked one.
     */
    @Test
    fun finishDialogSaysWhatGoes() {
        seedFinishedWorkout(System.currentTimeMillis() - 86_400_000L, bench(), 80.0, 8)
        startWorkoutFromHome()
        addExercise("bench", benchPress)
        clickText(str(Res.string.workout_add_set))
        waitForDescription(str(Res.string.workout_mark_done, 2))
        clickDescription(str(Res.string.workout_mark_done, 1))
        waitForDescription(str(Res.string.workout_mark_undone, 1))
        clickDescription(str(Res.string.workout_mark_done, 2))
        waitForDescription(str(Res.string.workout_mark_undone, 2))

        clickText(str(Res.string.workout_finish))
        waitFor(isDialog())
        assertShown(hasText(str(Res.string.workout_finish_body_clean)))
        screenshot("1-finish-dialog-clean")
        clickInDialog(str(Res.string.workout_keep_training))
        waitGone(isDialog())

        clickDescription(str(Res.string.workout_mark_undone, 2))
        waitForDescription(str(Res.string.workout_mark_done, 2))
        clickText(str(Res.string.workout_finish))
        waitFor(isDialog())
        assertShown(hasText(plural(Res.plurals.workout_finish_unlogged, 1, 1)))
        screenshot("2-finish-dialog-counts")
        clickInDialog(str(Res.string.workout_finish_confirm))

        waitForText(str(Res.string.summary_title))
        assertShown(hasText("1") and hasText(str(Res.string.stat_sets)))
        // The seeded workout's set and the one kept here; the un-ticked one is gone.
        assertEquals(2, count("SELECT COUNT(*) FROM set_entry"))
    }

    /**
     * "Remove exercise" asks when the exercise has a logged set ("Remove Bench
     * press and its logged set?"), keeps it on Cancel, removes it on Remove, and
     * removes an exercise with nothing logged at once (review 2026-10-29).
     */
    @Test
    fun removingAnExerciseWithLoggedSetsAsksFirst() {
        val open = "SELECT COUNT(*) FROM workout_exercise we JOIN workout w ON we.workoutId = w.id WHERE w.endedAt IS NULL"
        seedFinishedWorkout(System.currentTimeMillis() - 86_400_000L, bench(), 80.0, 8)
        startWorkoutFromHome()
        addExercise("bench", benchPress)
        clickDescription(str(Res.string.workout_mark_done, 1))
        waitForDescription(str(Res.string.workout_mark_undone, 1))

        clickDescription(str(Res.string.workout_exercise_menu, benchPress))
        clickText(str(Res.string.workout_remove_exercise))
        waitFor(isDialog())
        assertShown(hasText(plural(Res.plurals.workout_remove_exercise_title, 1, benchPress, 1)))
        screenshot("1-remove-asks")
        clickInDialog(str(Res.string.action_cancel))
        waitGone(isDialog())
        assertShown(hasText(benchPress))
        assertEquals(1, count(open))

        clickDescription(str(Res.string.workout_exercise_menu, benchPress))
        clickText(str(Res.string.workout_remove_exercise))
        clickInDialog(str(Res.string.workout_remove_exercise_confirm))
        waitUntil { count(open) == 0 }
        waitForText(str(Res.string.workout_empty))

        // Nothing logged: no question, the exercise goes at once.
        addExercise("bench", benchPress)
        waitUntil { count(open) == 1 }
        clickDescription(str(Res.string.workout_exercise_menu, benchPress))
        clickText(str(Res.string.workout_remove_exercise))
        waitUntil { count(open) == 0 }
        waitForText(str(Res.string.workout_empty))
        screenshot("2-removed")
    }
}
