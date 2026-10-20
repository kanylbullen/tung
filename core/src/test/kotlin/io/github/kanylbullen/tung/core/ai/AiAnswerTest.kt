package io.github.kanylbullen.tung.core.ai

import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.command.CandidateResolver
import io.github.kanylbullen.tung.core.command.ExerciseCandidate
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.command.PlannedSet
import io.github.kanylbullen.tung.core.extendedExerciseId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AiAnswerTest {

    private val keys = setOf("bench_press", "back_squat", "overhead_press", "deadlift")
    private val resolver = CandidateResolver(
        keys.map { ExerciseCandidate(catalogExerciseId(it), listOf(it.replace('_', ' ').replaceFirstChar(Char::uppercase)), nameKey = it) } +
            ExerciseCandidate(extendedExerciseId("Landmine_Press"), listOf("Landmine press"), extendedId = "Landmine_Press"),
    )

    private fun parsed(request: AiRequest, text: String): AiDraft {
        val parse = parseAiAnswer(request, text, keys)
        assertTrue("$parse", parse is AiParse.Parsed)
        return (parse as AiParse.Parsed).draft
    }

    private fun proposal(request: AiRequest, text: String): AiProposal {
        val resolution = runBlocking { resolveAiAnswer(parsed(request, text), resolver) }
        assertTrue("$resolution", resolution is AiResolution.Proposed)
        return (resolution as AiResolution.Proposed).proposal
    }

    @Test
    fun aValidProgramBecomesRoutinePlans() {
        val p = proposal(
            AiRequest.Program,
            """
            {"rationale": "Build on bench and squat.", "program_name": "Two days",
             "routines": [
               {"name": "Upper", "exercises": [{"key": "bench_press", "target_sets": 3, "target_reps": 8},
                                               {"name": "Landmine press", "target_sets": "3", "target_reps": null}]},
               {"name": "Lower", "exercises": [{"key": "back_squat", "target_sets": 5.0, "target_reps": 5}]}
             ]}
            """,
        ) as AiProposal.Program
        assertEquals("Two days", p.programName)
        assertEquals("Build on bench and squat.", p.rationale)
        assertEquals(0, p.dropped)
        val upper = p.routines[0].toPlan()
        assertEquals("Upper", upper.name)
        assertEquals(ExerciseRef.ById(catalogExerciseId("bench_press")), upper.exercises[0].exercise)
        assertEquals(8, upper.exercises[0].targetReps)
        // An extended entry is named so the handler creates its row.
        assertEquals(ExerciseRef.Extended("Landmine_Press"), upper.exercises[1].exercise)
        assertEquals(null, upper.exercises[1].targetReps)
        assertEquals(5, p.routines[1].exercises.single().targetSets)
    }

    @Test
    fun aKeyInTheCatalogueIsByKeyAndAnythingElseByName() {
        val draft = parsed(
            AiRequest.Program,
            """{"routines": [{"name": "A", "exercises": [
                {"key": "bench_press", "name": "ignored", "target_sets": 3},
                {"key": "cable_fly", "target_sets": 3},
                {"key": "not_a_key", "name": "Pec deck", "target_sets": 3}]}]}""",
        ) as AiDraft.Program
        assertEquals(
            listOf(ExerciseRef.ByKey("bench_press"), ExerciseRef.ByName("cable fly"), ExerciseRef.ByName("Pec deck")),
            draft.routines.single().exercises.map { it.exercise },
        )
        assertEquals(null, draft.programName)
    }

    @Test
    fun anExerciseThatResolvesToNothingIsDroppedNotShown() {
        val p = proposal(
            AiRequest.Program,
            """{"routines": [
                {"name": "A", "exercises": [{"key": "bench_press", "target_sets": 3, "target_reps": 8}, {"name": "Zercher squat", "target_sets": 3}]},
                {"name": "B", "exercises": [{"key": "moon_walk", "target_sets": 3}]}]}""",
        ) as AiProposal.Program
        assertEquals(listOf("A"), p.routines.map { it.name })
        assertEquals(listOf("bench_press"), p.routines.single().exercises.map { it.exercise.nameKey })
        // Zercher squat, moon walk, and routine B that was left empty.
        assertEquals(3, p.dropped)
    }

    @Test
    fun numbersOutsideWhatValidateAcceptsAreDropped() {
        val draft = parsed(
            AiRequest.Program,
            """{"routines": [{"name": "A", "exercises": [
                {"key": "bench_press", "target_sets": 0}, {"key": "deadlift", "target_sets": 21},
                {"key": "back_squat", "target_sets": 3, "target_reps": 1000}, {"key": "overhead_press", "target_sets": 3, "target_reps": 0}]},
                {"name": "  ", "exercises": [{"key": "bench_press", "target_sets": 3}]}]}""",
        ) as AiDraft.Program
        // target_reps 0 reads as "no target"; the rest is out of range, and a nameless routine goes too.
        assertEquals(listOf(ExerciseRef.ByKey("overhead_press")), draft.routines.single().exercises.map { it.exercise })
        assertEquals(null, draft.routines.single().exercises.single().targetReps)
        assertEquals(4, draft.dropped)

        val workout = parsed(
            AiRequest.NextWorkout,
            """{"exercises": [{"key": "bench_press", "sets": [{"weight_kg": 1200, "reps": 5}]},
                {"key": "deadlift", "sets": []}, {"key": "back_squat", "sets": [{"weight_kg": 100, "reps": 5}, {"reps": 5}]}]}""",
        ) as AiDraft.NextWorkout
        assertEquals(listOf(ExerciseRef.ByKey("back_squat")), workout.plan.exercises.map { it.exercise })
        assertEquals(listOf(PlannedSet(100.0, 5), PlannedSet(null, 5)), workout.plan.exercises.single().sets)
        assertEquals(2, workout.dropped)
    }

    @Test
    fun aPlanLeftEmptyIsRejected() {
        val program = parsed(AiRequest.Program, """{"routines": [{"name": "A", "exercises": [{"name": "Zercher squat", "target_sets": 3}]}]}""")
        val r1 = runBlocking { resolveAiAnswer(program, resolver) }
        assertTrue("$r1", r1 is AiResolution.Empty)

        val workout = parsed(AiRequest.NextWorkout, """{"exercises": [{"key": "moon_walk", "sets": [{"weight_kg": 10, "reps": 5}]}]}""")
        assertTrue(runBlocking { resolveAiAnswer(workout, resolver) } is AiResolution.Empty)

        val none = parsed(AiRequest.Program, """{"routines": []}""")
        assertTrue(runBlocking { resolveAiAnswer(none, resolver) } is AiResolution.Empty)
    }

    @Test
    fun malformedAnswersAreRejected() {
        listOf(
            "Sure! Here is your program.",
            "{\"routines\": [",
            "[1, 2, 3]",
            "{\"rationale\": \"no routines\"}",
            "",
        ).forEach { text ->
            val parse = parseAiAnswer(AiRequest.Program, text, keys)
            assertTrue("$text → $parse", parse is AiParse.Malformed)
        }
        assertTrue(parseAiAnswer(AiRequest.NextWorkout, "{\"routines\": []}", keys) is AiParse.Malformed)
        assertTrue(parseAiAnswer(AiRequest.Plateaus, "{\"exercises\": []}", keys) is AiParse.Malformed)
    }

    @Test
    fun aCodeFenceAroundTheObjectIsTolerated() {
        val p = proposal(
            AiRequest.NextWorkout,
            "```json\n{\"rationale\": \"Squat day.\", \"exercises\": [{\"key\": \"back_squat\", \"sets\": [{\"weight_kg\": 102.5, \"reps\": 5}]}]}\n```",
        ) as AiProposal.NextWorkout
        assertEquals("Squat day.", p.rationale)
        val plan = p.toPlan()
        assertEquals(ExerciseRef.ById(catalogExerciseId("back_squat")), plan.exercises.single().exercise)
        assertEquals(listOf(PlannedSet(102.5, 5)), plan.exercises.single().sets)
    }

    @Test
    fun plateausResolveTheirExerciseAndMayBeNone() {
        val p = proposal(
            AiRequest.Plateaus,
            """{"plateaus": [
                {"key": "bench_press", "since": "2026-08-01", "observation": "80 × 8 since August.", "suggestion": "Deload to 70 kg."},
                {"key": "moon_walk", "since": "2026-08-01", "observation": "x", "suggestion": "y"},
                {"name": "Deadlift", "since": "last summer", "observation": "140 × 3.", "suggestion": "Try 5s."}]}""",
        ) as AiProposal.Plateaus
        assertEquals(listOf("bench_press", "deadlift"), p.plateaus.map { it.exercise.nameKey })
        assertEquals(LocalDate.of(2026, 8, 1), p.plateaus[0].since)
        assertEquals(null, p.plateaus[1].since)
        assertEquals("Deload to 70 kg.", p.plateaus[0].suggestion)
        assertEquals(1, p.dropped)

        val none = proposal(AiRequest.Plateaus, """{"plateaus": []}""") as AiProposal.Plateaus
        assertTrue(none.plateaus.isEmpty())
    }
}
