package io.github.kanylbullen.tung.flows

import io.github.kanylbullen.tung.ui.resources.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.Muscle
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.ui.common.displayName
import io.github.kanylbullen.tung.ui.exercises.SUGGESTION_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The picker's "Suggested" section (issue #39): cold start, learned from history, hidden by search. */
class SuggestionsFlowTest : FlowTest() {

    private data class Row(val name: String, val reason: String)

    /** The "Suggested" rows on screen, top to bottom: each row's merged text is its name, then its reason. */
    private fun suggestionRows(): List<Row> =
        compose.onAllNodes(hasTestTag(SUGGESTION_TAG)).fetchSemanticsNodes().map { node ->
            val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            Row(texts.getOrElse(0) { "" }, texts.getOrElse(1) { "" })
        }

    /** Waits for the section to have [count] rows and returns them. */
    private fun waitForSuggestions(count: Int = 6): List<Row> {
        waitFor(hasText(str(R.string.picker_suggested)), timeoutMillis = 10_000)
        waitUntil(10_000) { suggestionRows().size >= count }
        return suggestionRows()
    }

    /** The main (first primary) muscle of the exercise displayed as [name], from the catalogue. */
    private fun mainMuscleOf(name: String): Muscle? = runBlocking {
        val exercise = app.container.exerciseRepository.observeExercises().first().first { it.displayName(app) == name }
        app.container.catalogueMuscles.index().musclesOf(exercise.id)?.main
    }

    /** Writes a finished workout through the real repository: [keys] in order, one ticked set each. */
    private fun seedWorkout(startedAt: Long, vararg keys: String) = runBlocking {
        var now = startedAt
        val repo = WorkoutRepository(db, clock = { now })
        val workoutId = repo.startOrResumeWorkout()
        keys.forEach { key ->
            val workoutExerciseId = repo.addExercise(workoutId, catalogExerciseId(key), sets = 1)
            val setId = db.openHelper.readableDatabase
                .query("SELECT id FROM set_entry WHERE workoutExerciseId = ?", arrayOf(workoutExerciseId))
                .use { c -> c.moveToFirst(); c.getString(0) }
            repo.updateWeight(setId, 20.0)
            repo.updateReps(setId, 10)
            repo.setCompleted(setId, true)
            now += 10 * 60_000L
        }
        check(repo.finishWorkout(workoutId)) { "seeded workout was discarded" }
    }

    /**
     * With no history, after Barbell curl the first suggestion is a triceps
     * exercise ("Pairs with biceps") and none of the first three trains
     * biceps; tapping a suggestion adds it like a row of the list does.
     */
    @Test
    fun coldStartSuggestsTricepsAfterBiceps() {
        startWorkoutFromHome()
        addExercise("barbell curl", str(R.string.ex_barbell_curl))
        clickText(str(R.string.workout_add_exercise))

        val rows = waitForSuggestions()
        screenshot("1-suggested")
        assertEquals(6, rows.size)
        assertEquals(rows.toString(), Muscle.TRICEPS, mainMuscleOf(rows.first().name))
        assertTrue(rows.toString(), rows.take(3).none { mainMuscleOf(it.name) == Muscle.BICEPS })
        assertEquals(str(R.string.picker_reason_pairs_with, str(Res.string.muscle_name_biceps)), rows.first().reason)

        val first = rows.first().name
        compose.onAllNodes(hasTestTag(SUGGESTION_TAG))[0].performClick()
        waitForText(str(R.string.workout_finish))
        waitForText(first)
        screenshot("2-suggestion-added")
        val exercise = runBlocking {
            app.container.exerciseRepository.observeExercises().first().first { it.displayName(app) == first }
        }
        assertEquals(1, count("SELECT COUNT(*) FROM workout_exercise WHERE exerciseId = '${exercise.id}'"))
    }

    /** Three finished workouts where Hammer curl followed Barbell curl: Hammer curl comes first, "Often after Barbell curl". */
    @Test
    fun historyPutsTheUsualNextExerciseFirst() {
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        seedWorkout(now - 7 * day, "barbell_curl", "hammer_curl")
        seedWorkout(now - 5 * day, "bench_press", "barbell_curl", "hammer_curl")
        seedWorkout(now - 3 * day, "barbell_curl", "hammer_curl", "triceps_pushdown")

        startWorkoutFromHome()
        addExercise("barbell curl", str(R.string.ex_barbell_curl))
        clickText(str(R.string.workout_add_exercise))

        val rows = waitForSuggestions()
        screenshot("1-learned")
        assertEquals(rows.toString(), str(R.string.ex_hammer_curl), rows.first().name)
        assertEquals(str(R.string.picker_reason_often_after, str(R.string.ex_barbell_curl)), rows.first().reason)
    }

    /** Typing hides the section, and search still finds everything. */
    @Test
    fun searchHidesTheSuggestions() {
        startWorkoutFromHome()
        addExercise("barbell curl", str(R.string.ex_barbell_curl))
        clickText(str(R.string.workout_add_exercise))
        waitForSuggestions()

        searchPicker("bench")
        waitGone(hasText(str(R.string.picker_suggested)))
        assertTrue(suggestionRows().isEmpty())
        waitForText(str(R.string.ex_bench_press))
        screenshot("1-search")

        // Clearing the search brings the section back.
        searchPicker("")
        waitFor(hasText(str(R.string.picker_suggested)))
    }

    /** A workout without exercises gets no section. */
    @Test
    fun anEmptyWorkoutHasNoSuggestions() {
        startWorkoutFromHome()
        clickText(str(R.string.workout_add_exercise))
        waitForText(str(R.string.ex_bench_press))
        assertTrue(compose.onAllNodes(hasText(str(R.string.picker_suggested))).fetchSemanticsNodes().isEmpty())
    }
}
