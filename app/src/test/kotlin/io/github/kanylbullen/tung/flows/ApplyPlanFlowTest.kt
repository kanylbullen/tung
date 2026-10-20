package io.github.kanylbullen.tung.flows

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.command.PlannedExercise
import io.github.kanylbullen.tung.core.command.WorkoutPlan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A whole workout dictated at once goes in through the command handler, the
 * same door as every button: "deadlift, bench press and barbell curl, five
 * sets of ten, 50, 60 and 20 kg".
 */
class ApplyPlanFlowTest : FlowTest() {

    private val deadlift get() = str(R.string.ex_deadlift)
    private val benchPress get() = str(R.string.ex_bench_press)
    private val barbellCurl get() = str(R.string.ex_barbell_curl)

    private fun execute(command: Command): CommandResult = runBlocking { app.container.commands.execute(command) }

    private fun setsOf(key: String, where: String = "1"): Int = count(
        "SELECT COUNT(*) FROM set_entry s JOIN workout_exercise we ON s.workoutExerciseId = we.id " +
            "WHERE we.exerciseId = '${catalogExerciseId(key)}' AND $where",
    )

    /**
     * The plan becomes the workout in progress: three exercises in order, five
     * un-ticked rows each with the planned numbers. The active workout shows
     * it, Log set aims at the first deadlift set, and finishing after one
     * logged set saves one workout with that set.
     */
    @Test
    fun aPlanBecomesTheWorkoutInProgress() {
        val result = execute(
            Command.ApplyPlan(
                WorkoutPlan(
                    listOf(
                        PlannedExercise.uniform(ExerciseRef.ByName("deadlift"), sets = 5, reps = 10, weightKg = 50.0),
                        PlannedExercise.uniform(ExerciseRef.ByName("Bänkpress"), sets = 5, reps = 10, weightKg = 60.0),
                        PlannedExercise.uniform(ExerciseRef.ByKey("barbell_curl"), sets = 5, reps = 10, weightKg = 20.0),
                    ),
                ),
            ),
        )
        assertTrue("$result", result is CommandResult.Done)
        val workoutId = checkNotNull((result as CommandResult.Done).ids[CommandIds.WORKOUT_ID])

        assertEquals(1, count("SELECT COUNT(*) FROM workout WHERE endedAt IS NULL"))
        assertEquals(3, count("SELECT COUNT(*) FROM workout_exercise WHERE workoutId = '$workoutId'"))
        assertEquals(
            "exercises in plan order",
            listOf("deadlift", "bench_press", "barbell_curl").map(::catalogExerciseId),
            db.openHelper.readableDatabase
                .query("SELECT exerciseId FROM workout_exercise WHERE workoutId = ? ORDER BY position", arrayOf(workoutId))
                .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } },
        )
        assertEquals(5, setsOf("deadlift", "s.weightKg = 50.0 AND s.reps = 10 AND s.completed = 0"))
        assertEquals(5, setsOf("bench_press", "s.weightKg = 60.0 AND s.reps = 10 AND s.completed = 0"))
        assertEquals(5, setsOf("barbell_curl", "s.weightKg = 20.0 AND s.reps = 10 AND s.completed = 0"))
        assertEquals(15, count("SELECT COUNT(*) FROM set_entry"))

        clickText(str(R.string.home_resume_workout))
        waitForText(deadlift)
        val logDeadlift = str(R.string.workout_log_set, deadlift, "50", "10")
        waitForText(logDeadlift)
        waitFor(hasContentDescription(str(R.string.workout_mark_done, 5)))
        screenshot("1-planned-workout")

        // Bench press: its header, then its first row at 60 × 10. Tapping the header makes that row current.
        val list = compose.onNode(hasScrollAction())
        list.performScrollToNode(hasText(benchPress))
        waitFor(hasContentDescription(str(R.string.workout_edit_weight, "60")))
        waitFor(hasContentDescription(str(R.string.workout_edit_reps, "10")))
        clickDescription(str(R.string.workout_select_exercise, benchPress))
        waitForText(str(R.string.workout_log_set, benchPress, "60", "10"))
        screenshot("2-bench-press")

        list.performScrollToNode(hasText(barbellCurl))
        waitFor(hasContentDescription(str(R.string.workout_edit_weight, "20")))
        list.performScrollToNode(hasContentDescription(str(R.string.workout_add_set_description, barbellCurl)))

        // Back to the deadlift and log its first set.
        list.performScrollToNode(hasText(deadlift))
        clickDescription(str(R.string.workout_select_exercise, deadlift))
        clickText(logDeadlift)
        waitForDescription(str(R.string.workout_mark_undone, 1))
        assertEquals(1, setsOf("deadlift", "s.completed = 1"))

        finishWorkout(screenshotStep = "3-finish")
        waitGone(hasText(str(R.string.workout_finish_title)))
        compose.waitUntil(15_000) { count("SELECT COUNT(*) FROM workout WHERE endedAt IS NOT NULL") == 1 }
        assertEquals("one workout, saved", 1, count("SELECT COUNT(*) FROM workout"))
        assertEquals("with the one logged set", 1, count("SELECT COUNT(*) FROM set_entry"))
        assertEquals(1, count("SELECT COUNT(*) FROM workout_exercise"))
        screenshot("4-summary")
    }

    /**
     * A plan that names something the catalogue does not have is a question,
     * with suggestions, and nothing is created: no workout, no exercise row.
     */
    @Test
    fun anUnknownExerciseIsAQuestionAndCreatesNothing() {
        val exercisesBefore = count("SELECT COUNT(*) FROM exercise")
        val result = execute(
            Command.ApplyPlan(
                WorkoutPlan(
                    listOf(
                        PlannedExercise.uniform(ExerciseRef.ByKey("deadlift"), sets = 5, reps = 10, weightKg = 50.0),
                        PlannedExercise.uniform(ExerciseRef.ByName("foo bar"), sets = 5, reps = 10, weightKg = 60.0),
                    ),
                ),
            ),
        )
        assertTrue("$result", result is CommandResult.NeedsAnswers)
        val question = (result as CommandResult.NeedsAnswers).questions.single()
        assertEquals("foo bar", question.text)
        assertTrue("suggestions for 'foo bar'", question.suggestions.isNotEmpty())

        assertEquals(0, count("SELECT COUNT(*) FROM workout"))
        assertEquals(0, count("SELECT COUNT(*) FROM workout_exercise"))
        assertEquals(0, count("SELECT COUNT(*) FROM set_entry"))
        assertEquals(exercisesBefore, count("SELECT COUNT(*) FROM exercise"))

        // Home still offers a fresh start, not a workout to resume.
        waitForText(str(R.string.home_start_workout))
        compose.onNode(hasText(str(R.string.home_resume_workout))).assertDoesNotExist()
    }
}
