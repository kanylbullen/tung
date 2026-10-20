package io.github.kanylbullen.tung.flows

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.WorkoutRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The current set: the one "Log set" acts on. It carries a marker, the button
 * names its exercise, a tap on a header or a set number moves it, logging the
 * last set of an exercise moves it to the next exercise, and the list scrolls
 * to what was just added.
 */
class CurrentSetFlowTest : FlowTest() {

    private val benchPress get() = str(R.string.ex_bench_press)
    private val backSquat get() = str(R.string.ex_back_squat)
    private val deadlift get() = str(R.string.ex_deadlift)
    private val overheadPress get() = str(R.string.ex_overhead_press)

    private fun logSet(exercise: String, weight: String, reps: String) =
        hasText(str(R.string.workout_log_set, exercise, weight, reps))

    private fun header(exercise: String) = hasContentDescription(str(R.string.workout_select_exercise, exercise))

    /** A set number marked as the current set. */
    private fun markedCurrent(number: Int) = hasContentDescription(str(R.string.workout_set_number_description, number)) and
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, str(R.string.workout_current_set))

    /**
     * Someone adds Bench press and then Back squat; the squat, just added, is
     * the current set. A tap on Bench press's name makes the button log bench
     * press, and a tap on Back squat's name switches back.
     */
    @Test
    fun aHeaderTapMakesThatExerciseCurrent() {
        startWorkoutFromHome()
        addExercise("bench", benchPress)
        addExercise("squat", backSquat)
        val empty = str(R.string.workout_reps_empty)
        waitFor(logSet(backSquat, "0", empty))
        screenshot("1-squat-current")

        clickDescription(str(R.string.workout_select_exercise, benchPress))
        waitFor(logSet(benchPress, "0", empty))
        screenshot("2-bench-current")

        clickDescription(str(R.string.workout_select_exercise, backSquat))
        waitFor(logSet(backSquat, "0", empty))
    }

    /**
     * Bench press with three sets planned, 80 × 8, 85 × 6 and 90 × 4. Set 1 is
     * current; a tap on the number 2 makes set 2 current, marked on its row,
     * and "Log set" ticks set 2.
     */
    @Test
    fun aSetNumberTapMakesThatSetCurrent() {
        seedActiveWorkout(bench() to listOf(80.0 to 8, 85.0 to 6, 90.0 to 4))
        clickText(str(R.string.home_resume_workout))
        waitFor(logSet(benchPress, "80", "8"))
        waitFor(markedCurrent(1))
        screenshot("1-set-1-current")

        clickDescription(str(R.string.workout_select_set, 2))
        waitFor(logSet(benchPress, "85", "6"))
        waitFor(markedCurrent(2))
        compose.onNode(hasContentDescription(str(R.string.workout_select_set, 1))).assertExists()
        screenshot("2-set-2-current")

        compose.onNode(logSet(benchPress, "85", "6")).performSemanticsAction(SemanticsActions.OnClick)
        waitForDescription(str(R.string.workout_mark_undone, 2))
        waitUntil(5_000) { activeSets("s.completed = 1") == 1 }
        assertEquals(1, activeSets("s.completed = 1 AND s.position = 1 AND s.weightKg = 85.0"))
    }

    /**
     * A routine of bench press, back squat and deadlift, one set each. Someone
     * picks the squat first by its name and logs it: the current set moves on
     * to the deadlift, the next exercise in the list, not back to bench press.
     * Logging the deadlift wraps around to bench press.
     */
    @Test
    fun loggingTheLastSetMovesToTheNextExerciseAndWraps() {
        val source = seedFinishedWorkout(
            System.currentTimeMillis() - 3 * DAY,
            listOf(bench() to (80.0 to 8), squat() to (100.0 to 5), catalogExerciseId("deadlift") to (120.0 to 3)),
        )
        runBlocking {
            val routines = RoutineRepository(db)
            checkNotNull(routines.start(checkNotNull(routines.saveFromWorkout(source, "Full body"))))
        }
        clickText(str(R.string.home_resume_workout))
        waitFor(logSet(benchPress, "80", "8"))

        clickDescription(str(R.string.workout_select_exercise, backSquat))
        waitFor(logSet(backSquat, "100", "5"))
        screenshot("1-squat-picked")

        compose.onNode(logSet(backSquat, "100", "5")).performSemanticsAction(SemanticsActions.OnClick)
        waitFor(logSet(deadlift, "120", "3"))
        screenshot("2-moved-to-deadlift")

        compose.onNode(logSet(deadlift, "120", "3")).performSemanticsAction(SemanticsActions.OnClick)
        waitFor(logSet(benchPress, "80", "8"))
        screenshot("3-wrapped-to-bench")
        assertEquals(2, activeSets("s.completed = 1"))
        assertEquals(3, activeSets("1"))
    }

    /**
     * A long workout: back squat with two sets, then bench press with six,
     * whose "+ Set" starts under the Log set button. Someone adds a seventh
     * set to bench press; the list scrolls so the new set is wholly visible
     * above the button.
     */
    @Test
    fun aNewSetIsScrolledIntoView() {
        seedLongWorkout()
        clickText(str(R.string.home_resume_workout))
        val squatButton = logSet(backSquat, "100", "5")
        waitFor(squatButton)

        val addBenchSet = hasContentDescription(str(R.string.workout_add_set_description, benchPress))
        waitFor(addBenchSet)
        // The premise: without a scroll the new set, which takes the place of "+ Set", would be hidden.
        assertTrue(
            "+ Set is clear of the Log set button, so this test would prove nothing",
            compose.onNode(addBenchSet).getUnclippedBoundsInRoot().bottom > compose.onNode(squatButton).getUnclippedBoundsInRoot().top,
        )
        screenshot("1-add-set-under-the-button")
        // Through the semantics action: the row sits under the Log set button, where a tap would hit the button.
        compose.onNode(addBenchSet).performSemanticsAction(SemanticsActions.OnClick)

        val newSet = hasContentDescription(str(R.string.workout_mark_done, 7))
        waitFor(newSet)
        // Adding a set touches bench press, and with no set picked by hand its first open set is current.
        val button = logSet(benchPress, "80", "8")
        waitUntil(5_000) {
            compose.onNode(newSet).getUnclippedBoundsInRoot().bottom <= compose.onNode(button).getUnclippedBoundsInRoot().top
        }
        compose.onNode(newSet).assertIsDisplayed()
        screenshot("2-new-set-in-view")
    }

    /**
     * In the same long workout someone adds Overhead press at the end. Back on
     * the workout it is the current exercise, and the list has scrolled to it:
     * its header and its first set are on screen, clear of the Log set button.
     * (Without the scroll the header would take the place of "Add exercise",
     * near the bottom, and its set would be under the button.)
     */
    @Test
    fun aNewExerciseIsScrolledToTheTop() {
        seedLongWorkout()
        clickText(str(R.string.home_resume_workout))
        waitFor(logSet(backSquat, "100", "5"))

        compose.onNode(hasScrollAction()).performScrollToNode(hasText(str(R.string.workout_add_exercise)))
        compose.onNode(hasText(str(R.string.workout_add_exercise))).performSemanticsAction(SemanticsActions.OnClick)
        searchPicker("overhead press")
        clickText(overheadPress)
        waitForText(str(R.string.workout_finish))

        val button = logSet(overheadPress, "0", str(R.string.workout_reps_empty))
        waitFor(button)
        val newHeader = header(overheadPress)
        waitFor(newHeader)
        val newSet = markedCurrent(1)
        waitFor(newSet)
        waitUntil(5_000) {
            compose.onNode(newSet).getUnclippedBoundsInRoot().bottom <= compose.onNode(button).getUnclippedBoundsInRoot().top
        }
        compose.onNode(newHeader).assertIsDisplayed()
        screenshot("1-new-exercise-at-top")
    }

    /** A set chosen by its number is still the current set after the activity is recreated. */
    @Test
    fun aManualSelectionSurvivesRecreation() {
        seedActiveWorkout(bench() to listOf(80.0 to 8, 85.0 to 6, 90.0 to 4))
        clickText(str(R.string.home_resume_workout))
        waitFor(logSet(benchPress, "80", "8"))
        clickDescription(str(R.string.workout_select_set, 3))
        waitFor(logSet(benchPress, "90", "4"))

        compose.activityRule.scenario.recreate()
        waitFor(logSet(benchPress, "90", "4"))
        waitFor(markedCurrent(3))
        screenshot("1-after-recreate")
    }

    // --- seeding ---

    private fun squat(): String = catalogExerciseId("back_squat")

    /** Back squat with two sets, bench press with six and deadlift with three: longer than the screen. */
    private fun seedLongWorkout() = seedActiveWorkout(
        squat() to List(2) { 100.0 to 5 },
        bench() to List(6) { 80.0 to 8 },
        catalogExerciseId("deadlift") to List(3) { 120.0 to 3 },
    )

    /** A workout in progress with these exercises and un-ticked sets, as if planned in the app. */
    private fun seedActiveWorkout(vararg exercises: Pair<String, List<Pair<Double, Int>>>): String = runBlocking {
        val repo = WorkoutRepository(db)
        val workoutId = repo.startOrResumeWorkout()
        exercises.forEach { (exerciseId, sets) ->
            val workoutExerciseId = repo.addExercise(workoutId, exerciseId)
            repeat(sets.size - 1) { repo.addSet(workoutExerciseId) }
            setIds(workoutExerciseId).zip(sets).forEach { (id, set) ->
                repo.updateWeight(id, set.first)
                repo.updateReps(id, set.second)
            }
        }
        workoutId
    }

    /** A finished workout with one ticked set per exercise. */
    private fun seedFinishedWorkout(startedAt: Long, exercises: List<Pair<String, Pair<Double, Int>>>): String = runBlocking {
        var now = startedAt
        val repo = WorkoutRepository(db, clock = { now })
        val workoutId = repo.startOrResumeWorkout()
        exercises.forEach { (exerciseId, set) ->
            val id = setIds(repo.addExercise(workoutId, exerciseId)).single()
            repo.updateWeight(id, set.first)
            repo.updateReps(id, set.second)
            repo.setCompleted(id, true)
        }
        now = startedAt + 45 * 60_000L
        check(repo.finishWorkout(workoutId)) { "seeded workout was discarded" }
        workoutId
    }

    private fun setIds(workoutExerciseId: String): List<String> = db.openHelper.readableDatabase
        .query("SELECT id FROM set_entry WHERE workoutExerciseId = ? ORDER BY position", arrayOf(workoutExerciseId))
        .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private fun activeSets(where: String): Int = count(
        "SELECT COUNT(*) FROM set_entry s JOIN workout_exercise we ON s.workoutExerciseId = we.id " +
            "JOIN workout w ON we.workoutId = w.id WHERE w.endedAt IS NULL AND $where",
    )

    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }
}
