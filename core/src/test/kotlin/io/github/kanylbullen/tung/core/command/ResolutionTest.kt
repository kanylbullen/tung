package io.github.kanylbullen.tung.core.command

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.extendedExerciseId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolutionTest {

    private fun core(key: String, en: String, sv: String) = ExerciseCandidate(catalogExerciseId(key), listOf(en, sv), nameKey = key)

    private val deadlift = core("deadlift", "Deadlift", "Marklyft")
    private val bench = core("bench_press", "Bench press", "Bänkpress")
    private val incline = core("incline_bench_press", "Incline bench press", "Lutande bänkpress")
    private val barbellCurl = core("barbell_curl", "Barbell curl", "Skivstångscurl")
    private val dumbbellCurl = core("dumbbell_curl", "Dumbbell curl", "Hantelcurl")
    private val hammerCurl = core("hammer_curl", "Hammer curl", "Hammercurl")
    private val chinUp = core("chin_up", "Chin-up", "Chins")
    private val extendedZercher = ExerciseCandidate(extendedExerciseId("Zercher_Squat"), listOf("Zercher Squat"), extendedId = "Zercher_Squat")
    private val extendedDeadlift = ExerciseCandidate(extendedExerciseId("Deadlift_X"), listOf("Deadlift"), extendedId = "Deadlift_X")

    private val catalogue = listOf(deadlift, bench, incline, barbellCurl, dumbbellCurl, hammerCurl, chinUp, extendedZercher, extendedDeadlift)
    private val resolver = CandidateResolver(catalogue)

    private fun match(text: String) = matchExerciseName(text, catalogue)

    @Test
    fun anExactNameResolvesInEitherLanguage() {
        assertEquals(NameMatch.Exact(bench), match("Bench press"))
        assertEquals(NameMatch.Exact(bench), match("Bänkpress"))
        assertEquals(NameMatch.Exact(deadlift), match("marklyft"))
    }

    @Test
    fun caseDiacriticsAndPunctuationDoNotMatter() {
        assertEquals(NameMatch.Exact(bench), match("bankpress"))
        assertEquals(NameMatch.Exact(bench), match("  BÄNKPRESS "))
        assertEquals(NameMatch.Exact(chinUp), match("chin up"))
        assertEquals(NameMatch.Exact(chinUp), match("CHIN-UP"))
    }

    @Test
    fun aPartialNameIsAQuestionWithSuggestionsNotAGuess() {
        val curl = match("curl")
        assertTrue(curl is NameMatch.Unsure)
        val names = (curl as NameMatch.Unsure).suggestions.map { it.nameKey }
        assertEquals(setOf("barbell_curl", "dumbbell_curl", "hammer_curl"), names.toSet())

        val bicepsCurl = match("biceps curl") as NameMatch.Unsure
        assertTrue(bicepsCurl.suggestions.map { it.nameKey }.containsAll(listOf("barbell_curl", "dumbbell_curl", "hammer_curl")))

        // Even a prefix that only one exercise has is not taken as that exercise.
        assertTrue(match("Bench") is NameMatch.Unsure)
    }

    @Test
    fun suggestionsPutContainingNamesFirst() {
        val suggestions = (match("bench") as NameMatch.Unsure).suggestions
        assertEquals(listOf(bench, incline), suggestions)
    }

    @Test
    fun unrelatedWordsGetSuggestionsFromSharedWordStarts() {
        val foo = match("foo bar") as NameMatch.Unsure
        assertEquals(listOf(barbellCurl), foo.suggestions)
        assertEquals(NameMatch.Unsure(emptyList()), match("xyzzy"))
    }

    @Test
    fun twoExercisesWithTheSameNameAreAQuestion() {
        val custom = ExerciseCandidate("custom-1", listOf("Bench press"))
        val result = matchExerciseName("bench press", catalogue + custom) as NameMatch.Unsure
        assertEquals(setOf(bench, custom), result.suggestions.toSet())
    }

    @Test
    fun aRowBeatsAnExtendedEntryOfTheSameNameAndAnExtendedOnlyNameResolves() {
        assertEquals(NameMatch.Exact(deadlift), match("Deadlift"))
        assertEquals(NameMatch.Exact(extendedZercher), match("zercher squat"))
    }

    @Test
    fun applyPlanResolvesEveryExerciseAndKeepsTheSets() = runBlocking {
        val plan = WorkoutPlan(
            listOf(
                PlannedExercise.uniform(ExerciseRef.ByKey("deadlift"), sets = 5, reps = 10, weightKg = 50.0),
                PlannedExercise.uniform(ExerciseRef.ByName("bänkpress"), sets = 5, reps = 10, weightKg = 60.0),
                PlannedExercise.uniform(ExerciseRef.ById(barbellCurl.id), sets = 5, reps = 10, weightKg = 20.0),
                PlannedExercise.uniform(ExerciseRef.Extended("Zercher_Squat"), sets = 3, reps = null, weightKg = null),
            ),
        )
        val resolved = resolvePlan(plan, resolver)
        assertTrue(resolved is PlanResolution.Resolved)
        val exercises = (resolved as PlanResolution.Resolved).plan.exercises
        assertEquals(listOf(deadlift.id, bench.id, barbellCurl.id, extendedZercher.id), exercises.map { it.exercise.id })
        assertEquals(List(5) { PlannedSet(60.0, 10) }, exercises[1].sets)
        assertEquals("Zercher_Squat", exercises[3].exercise.extendedId)
    }

    @Test
    fun anUnknownNameMakesThePlanAQuestionInPlanOrder() = runBlocking {
        val plan = WorkoutPlan(
            listOf(
                PlannedExercise.uniform(ExerciseRef.ByKey("deadlift"), 5, 10, 50.0),
                PlannedExercise.uniform(ExerciseRef.ByName("foo bar"), 5, 10, 60.0),
                PlannedExercise.uniform(ExerciseRef.ByName("biceps curl"), 5, 10, 20.0),
            ),
        )
        val result = resolvePlan(plan, resolver)
        assertTrue(result is PlanResolution.NeedsAnswers)
        val questions = (result as PlanResolution.NeedsAnswers).questions
        assertEquals(listOf("foo bar", "biceps curl"), questions.map { it.text })
        assertTrue(questions.all { it.suggestions.isNotEmpty() })
    }

    @Test
    fun anUnknownKeyOrIdIsAQuestionToo() = runBlocking {
        val result = resolvePlan(
            WorkoutPlan(
                listOf(
                    PlannedExercise.uniform(ExerciseRef.ByKey("bench_presss"), 3, 8, null),
                    PlannedExercise.uniform(ExerciseRef.ById("nope"), 3, 8, null),
                    PlannedExercise.uniform(ExerciseRef.Extended("nope"), 3, 8, null),
                ),
            ),
            resolver,
        ) as PlanResolution.NeedsAnswers
        assertEquals(listOf("bench_presss", "nope", "nope"), result.questions.map { it.text })
        assertEquals(bench, result.questions[0].suggestions.first())
    }

    @Test
    fun aRoutinePlanResolvesTheSameWay() = runBlocking {
        val plan = RoutinePlan(" Pull ", listOf(RoutinePlanExercise(ExerciseRef.ByName("Marklyft"), 3, 5)))
        val resolved = resolveRoutinePlan(plan, resolver) as PlanResolution.Resolved
        assertEquals("Pull", resolved.plan.name)
        assertEquals(ResolvedRoutineExercise(deadlift, 3, 5), resolved.plan.exercises.single())
    }

    @Test
    fun missingPlannedNumbersComeFromThePrefillRule() {
        val previous = listOf(LoggedSet(80.0, 8), LoggedSet(85.0, 6))
        assertEquals(
            listOf(LoggedSet(80.0, 10), LoggedSet(80.0, 10), LoggedSet(70.0, 10)),
            plannedSetValues(listOf(PlannedSet(null, 10), PlannedSet(null, null), PlannedSet(70.0, null)), previous),
        )
        assertEquals(listOf(LoggedSet(0.0, 0), LoggedSet(20.0, 0)), plannedSetValues(listOf(PlannedSet(null, null), PlannedSet(20.0, null)), emptyList()))
        assertEquals(List(5) { LoggedSet(60.0, 10) }, plannedSetValues(List(5) { PlannedSet(60.0, 10) }, previous))
    }

    @Test
    fun uniformBuildsIdenticalSets() {
        val planned = PlannedExercise.uniform(ExerciseRef.ByKey("deadlift"), sets = 5, reps = 10, weightKg = 50.0)
        assertEquals(List(5) { PlannedSet(50.0, 10) }, planned.sets)
    }
}
