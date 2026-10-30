package io.github.kanylbullen.tung.core.picker

import io.github.kanylbullen.tung.core.CoreExercise
import io.github.kanylbullen.tung.core.Muscle
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.MuscleIndex
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.command.ExerciseRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionsTest {

    private data class Entry(val key: String, val name: String, val group: String, val primary: List<String>)

    private val entries = listOf(
        Entry("barbell_curl", "Barbell curl", "biceps", listOf("biceps")),
        Entry("dumbbell_curl", "Dumbbell curl", "biceps", listOf("biceps")),
        Entry("hammer_curl", "Hammer curl", "biceps", listOf("biceps", "forearms")),
        Entry("triceps_pushdown", "Triceps pushdown", "triceps", listOf("triceps")),
        Entry("skull_crusher", "Skull crusher", "triceps", listOf("triceps")),
        Entry("bench_press", "Bench press", "chest", listOf("chest")),
        Entry("lat_pulldown", "Lat pulldown", "back", listOf("lats")),
        Entry("back_squat", "Back squat", "legs", listOf("quads", "glutes")),
        Entry("leg_curl", "Leg curl", "legs", listOf("hamstrings")),
        Entry("lateral_raise", "Lateral raise", "shoulders", listOf("side_delts")),
        Entry("plank", "Plank", "core", listOf("abs")),
        Entry("russian_twist", "Russian twist", "core", listOf("obliques")),
    )

    private val index = MuscleIndex.build(
        entries.map { CoreExercise(it.key, mapOf("en" to it.name), it.group, "other", it.primary) },
        emptyList(),
    )

    private val candidates = entries.map { Candidate(id(it.key), it.name, MuscleGroup.fromKey(it.group)) }

    private fun id(key: String) = catalogExerciseId(key)

    private fun workout(vararg keys: String) = keys.map { ExerciseRef.ByKey(it) }

    private fun keyOf(suggestion: Suggestion): String = entries.first { id(it.key) == suggestion.exerciseId }.key

    private val now = 1_000_000_000L
    private val day = 86_400_000L

    /** [keys] as a finished workout [daysAgo] days before [now]. */
    private fun past(daysAgo: Int, vararg keys: String) = HistoricWorkout(now - daysAgo * day, keys.map(::id))

    private fun run(current: List<ExerciseRef>, history: List<HistoricWorkout> = emptyList(), limit: Int = 6) =
        suggest(current, history, candidates, index, now, limit)

    @Test
    fun coldStartAfterABicepsExercisePutsTricepsFirstAndNoBicepsInTheTopThree() {
        val result = run(workout("barbell_curl"))
        assertEquals(6, result.size)
        assertEquals(Muscle.TRICEPS, index.musclesOf(result.first().exerciseId)?.main)
        assertTrue(result.take(3).none { index.musclesOf(it.exerciseId)?.main == Muscle.BICEPS })
        // Equal scores fall back to the name: Skull crusher before Triceps pushdown.
        assertEquals(listOf("skull_crusher", "triceps_pushdown"), result.take(2).map(::keyOf))
        assertEquals(SuggestionReason.PairsWith(Muscle.BICEPS), result.first().reason)
        assertEquals(SuggestionReason.NotYetToday, result.first { keyOf(it) == "bench_press" }.reason)
    }

    @Test
    fun theOtherComplementsWork() {
        assertEquals("leg_curl", keyOf(run(workout("back_squat")).first()))
        assertEquals("lat_pulldown", keyOf(run(workout("bench_press")).first()))
    }

    @Test
    fun learnedHistoryBeatsTheHeuristic() {
        val history = listOf(
            past(3, "barbell_curl", "hammer_curl"),
            past(6, "barbell_curl", "hammer_curl", "bench_press"),
            past(9, "back_squat", "barbell_curl", "hammer_curl"),
        )
        val first = run(workout("barbell_curl"), history).first()
        assertEquals("hammer_curl", keyOf(first))
        assertEquals(SuggestionReason.OftenAfter(id("barbell_curl")), first.reason)
    }

    @Test
    fun theHeuristicWinsBeforeTheHandOver() {
        // Seen once, a weak co-occurrence does not outrank a complement.
        val history = listOf(past(200, "bench_press", "dumbbell_curl"))
        val result = run(workout("bench_press", "barbell_curl"), history)
        assertEquals(Muscle.TRICEPS, index.musclesOf(result.first().exerciseId)?.main)
    }

    @Test
    fun recentWorkoutsWeighMore() {
        val history = listOf(
            past(30, "barbell_curl", "dumbbell_curl"),
            past(1, "barbell_curl", "hammer_curl"),
        )
        val order = run(workout("barbell_curl"), history, limit = 12).map(::keyOf)
        assertTrue(order.indexOf("hammer_curl") < order.indexOf("dumbbell_curl"))
    }

    @Test
    fun onlyTheLatestSixtyWorkoutsCount() {
        val oldest = past(100, "barbell_curl", "lateral_raise")
        val recent = (1..60).map { past(it, "bench_press") }
        val withoutOldest = run(workout("barbell_curl"), recent).first { keyOf(it) == "lateral_raise" }
        val withOldest = run(workout("barbell_curl"), recent + oldest).first { keyOf(it) == "lateral_raise" }
        assertEquals(withoutOldest, withOldest)
        // The 60th still counts, at half the weight of the newest.
        val sixtieth = run(workout("barbell_curl"), recent.dropLast(1) + past(99, "barbell_curl", "lateral_raise"))
            .first { keyOf(it) == "lateral_raise" }
        val newest = run(workout("barbell_curl"), listOf(past(0, "barbell_curl", "lateral_raise")) + recent.drop(1))
            .first { keyOf(it) == "lateral_raise" }
        assertEquals(newest.score - withoutOldest.score, 2 * (sixtieth.score - withoutOldest.score), 1e-9)
    }

    @Test
    fun workoutsAfterNowAreIgnored() {
        val future = HistoricWorkout(now + day, listOf(id("barbell_curl"), id("hammer_curl")))
        assertEquals(run(workout("barbell_curl")), run(workout("barbell_curl"), listOf(future)))
    }

    @Test
    fun exercisesAlreadyInTheWorkoutAreNotSuggested() {
        val history = listOf(past(1, "barbell_curl", "triceps_pushdown"), past(2, "triceps_pushdown", "barbell_curl"))
        val result = run(workout("barbell_curl", "triceps_pushdown"), history, limit = 12).map(::keyOf)
        assertTrue(result.isNotEmpty())
        assertTrue("barbell_curl" !in result && "triceps_pushdown" !in result)
    }

    @Test
    fun anEmptyWorkoutGetsNoSuggestions() {
        assertEquals(emptyList<Suggestion>(), run(emptyList(), listOf(past(1, "barbell_curl", "hammer_curl"))))
        // Words that are not resolved yet name no exercise either.
        assertEquals(emptyList<Suggestion>(), run(listOf(ExerciseRef.ByName("curls"))))
    }

    @Test
    fun coreComesLastUnlessNothingElseIsLeft() {
        val result = run(workout("bench_press"), limit = 12).map(::keyOf)
        assertTrue("plank" !in result && "russian_twist" !in result)
        val allButCore = entries.map { it.key }.filter { it != "plank" && it != "russian_twist" }
        val onlyCore = run(workout(*allButCore.toTypedArray()), limit = 12).map(::keyOf)
        assertEquals(listOf("plank", "russian_twist"), onlyCore)
    }

    @Test
    fun theSameMuscleAsTheLastExerciseIsDemoted() {
        val result = run(workout("bench_press", "barbell_curl"), limit = 12).map(::keyOf)
        assertTrue("dumbbell_curl" !in result && "hammer_curl" !in result)
    }

    @Test
    fun customExercisesUseTheirStoredGroup() {
        val custom = Candidate("custom-1", "Neck curl", MuscleGroup.SHOULDERS)
        val result = suggest(workout("barbell_curl"), emptyList(), candidates + custom, index, now, limit = 12)
        assertEquals(SuggestionReason.NotYetToday, result.first { it.exerciseId == "custom-1" }.reason)
    }
}
