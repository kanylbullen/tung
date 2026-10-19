package io.github.kanylbullen.tung.flows

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.ExtendedCatalog
import io.github.kanylbullen.tung.core.extendedExerciseId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Collator
import java.util.Locale

class PickerFlowTest : FlowTest() {

    /**
     * In the exercise picker, "bench" finds Bench press, "zzzz" says no exercise
     * has that name, and "glute" lists extended-catalogue exercises under "More
     * exercises"; tapping the first of those puts it in the workout.
     */
    @Test
    fun searchFindsCoreNothingAndExtended() {
        startWorkoutFromHome()
        clickText(str(R.string.workout_add_exercise))

        searchPicker("bench")
        waitForText(str(R.string.ex_bench_press))
        screenshot("1-bench")

        searchPicker("zzzz")
        waitForText(str(R.string.exercises_no_match))
        compose.onNode(hasText(str(R.string.ex_bench_press))).assertDoesNotExist()
        screenshot("2-no-match")

        // What "More exercises" should start with, worked out from the catalogue itself.
        val expected = runBlocking {
            val inRoom = app.container.exerciseRepository.observeExercises().first().mapTo(HashSet()) { it.id }
            val all = app.container.extendedCatalog.all()
            val collator = Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.SECONDARY }
            ExtendedCatalog.search(all, inRoom, "glute").sortedWith(compareBy(collator) { it.displayName })
        }
        assertTrue("the extended catalogue has no glute exercise", expected.isNotEmpty())
        val first = expected.first()

        searchPicker("glute")
        val list = compose.onNode(hasScrollAction())
        waitFor(hasText(str(R.string.picker_more)), timeoutMillis = 10_000)
        list.performScrollToNode(hasText(str(R.string.picker_more)))
        list.performScrollToNode(hasText(first.displayName))
        assertShown(hasText(first.displayName))
        screenshot("3-more-exercises")

        clickText(first.displayName)
        waitForText(str(R.string.workout_finish))
        waitForText(first.displayName)
        screenshot("4-extended-in-workout")

        assertEquals(
            1,
            count("SELECT COUNT(*) FROM workout_exercise WHERE exerciseId = '${extendedExerciseId(first.id)}'"),
        )
        assertEquals(1, count("SELECT COUNT(*) FROM exercise WHERE id = '${extendedExerciseId(first.id)}' AND isCustom = 0"))
    }
}
