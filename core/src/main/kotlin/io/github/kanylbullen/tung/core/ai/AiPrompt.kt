package io.github.kanylbullen.tung.core.ai

import io.github.kanylbullen.tung.core.command.ExerciseCandidate
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The questions the in-app assistant asks: the three the MCP server ships as
 * prompts ("Suggest a program", "Next workout", "Where have I got stuck"),
 * and [FreeText], what the user said to the workout's microphone that the
 * voice grammar did not understand. Each is answered by one model call, on a
 * tap, never in the background.
 */
sealed interface AiRequest {
    /** A stable name, for logs and tests. */
    val key: String

    /** A program: one routine per training day, for `save_routine_plan`, `create_program` and `add_program_day`. */
    data object Program : AiRequest {
        override val key = "program"
    }

    /** Today's workout as one plan, for `apply_plan`. */
    data object NextWorkout : AiRequest {
        override val key = "next_workout"
    }

    /** Where the user has stopped progressing; shown, never applied. */
    data object Plateaus : AiRequest {
        override val key = "plateaus"
    }

    /**
     * Words the user said in the active workout that the voice grammar
     * (`core/voice/`) did not understand, such as "bench, then three sets of
     * curls at twelve kilos". Answered as one workout plan, in the shape of
     * [NextWorkout] and read by the same parser, for `apply_plan`.
     */
    data class FreeText(val text: String) : AiRequest {
        override val key = "free_text"
    }

    /**
     * The import review's names that matched nothing exactly ([names], in the
     * review's order), each with its offline suggestions ([candidates], best
     * first, from `importSuggestions`), and the core catalogue
     * ([catalogue]: key, English name, equipment). Answered with one match
     * per name or none; shown on the review's rows, never applied by itself.
     * It carries everything it sends: no training data goes with it
     * ([buildAiMatchContext]).
     */
    data class MatchExercises(
        val names: List<String>,
        val candidates: Map<String, List<ExerciseCandidate>>,
        val catalogue: List<AiCatalogueEntry>,
    ) : AiRequest {
        override val key = "match_exercises"
    }
}

/** What is sent as text: a [system] message (the rules and the answer's shape) and a [user] message (the question and the data). */
data class AiPrompt(val system: String, val user: String)

/** The context's JSON in the user message: compact, every field written. */
internal val AiContextJson = Json { encodeDefaults = true }

/**
 * The prompt for [request] over [context]. English, like the MCP prompts it
 * follows; [language] (a BCP 47 tag, the language the app displays) names,
 * through [aiLanguageName], the language the model writes its text fields
 * in. The model is told to answer with one JSON object in the shape
 * [parseAiAnswer] reads and nothing else.
 */
fun buildAiPrompt(request: AiRequest, context: AiContext, language: String = "en"): AiPrompt {
    // Matching sends only what the request carries: never the training data in [context].
    if (request is AiRequest.MatchExercises) return buildAiMatchPrompt(request, language)
    val system = listOf(SYSTEM_INTRO, schemaFor(request), rules(language)).joinToString("\n\n")
    val user = taskFor(request) + equipmentRule(request, context) + "\n\nMy training data (JSON):\n" + AiContextJson.encodeToString(AiContext.serializer(), context)
    return AiPrompt(system = system, user = user)
}

private const val SYSTEM_INTRO =
    "You are the training assistant inside Tung, a strength training log. You get the user's training data as JSON: " +
        "finished workouts newest first (each exercise by catalogue key and name, with its logged sets, weight_kg in kilograms " +
        "and reps), routines (workout templates with target_sets and target_reps), programs (fixed: each day on its ISO " +
        "weekdays, 1 Monday to 7 Sunday; rotating: the days in order, continuing after last_completed_day), today's date and " +
        "weekday, catalogue_keys, the exercises you may use, and, when the user has narrowed it, equipment, what they " +
        "have to train with. You answer one question.\n\n" +
        "Answer with exactly one JSON object and nothing else: no Markdown, no code fence, no text before or after it. " +
        "The object has this shape:"

private fun rules(language: String): String =
    "Rules:\n" +
        "- Name each exercise by its catalogue key: \"key\": \"bench_press\", taken from catalogue_keys. Only when no key " +
        "fits, give its usual English name instead: \"name\": \"Cable fly\". An exercise that is not in the catalogue is " +
        "dropped, so prefer keys.\n" +
        "- Weights are in kilograms. Base every number on the history; never invent history the data does not show.\n" +
        "- Write the text fields (names, rationale, observation, suggestion) in ${aiLanguageName(language)}.\n" +
        "- Keep text short and plain."

private fun schemaFor(request: AiRequest): String = when (request) {
    AiRequest.Program ->
        """
        {
          "rationale": "One paragraph: why this program, and how to progress week to week.",
          "program_name": "A short name for the program",
          "routines": [
            {
              "name": "A short name for the training day",
              "exercises": [
                { "key": "bench_press", "target_sets": 3, "target_reps": 8 }
              ]
            }
          ]
        }
        One routine per training day, in the order they are trained. target_sets is 1 to 20; target_reps is 1 to 999, or
        null for none.
        """.trimIndent()
    AiRequest.NextWorkout, is AiRequest.FreeText ->
        """
        {
          "rationale": "One paragraph: why this workout today, and why these numbers.",
          "exercises": [
            { "key": "back_squat", "sets": [ { "weight_kg": 100, "reps": 5 } ] }
          ]
        }
        Exercises in the order they are done. Each has 1 to 50 sets; weight_kg is 0 to 999, reps 1 to 999.
        """.trimIndent()
    AiRequest.Plateaus ->
        """
        {
          "plateaus": [
            {
              "key": "bench_press",
              "since": "2026-08-01",
              "observation": "What the numbers show, with the numbers.",
              "suggestion": "One concrete change, and why."
            }
          ]
        }
        since is the ISO date of the session from which there has been no progress. An empty list means no plateaus.
        """.trimIndent()
    is AiRequest.MatchExercises -> error("built by buildAiMatchPrompt")
}

/**
 * A program or a next workout stays within "My equipment" when the user has
 * narrowed it ([AiContext.equipment]); the other questions are about what was
 * done, so they do not get the rule.
 */
private fun equipmentRule(request: AiRequest, context: AiContext): String {
    val equipment = context.equipment ?: return ""
    if (request != AiRequest.Program && request != AiRequest.NextWorkout) return ""
    return " I only have this equipment: " + equipment.joinToString(", ") + " (the \"equipment\" field of my data). " +
        "Use only exercises done with it; leave out anything that needs other equipment, even lifts in my history."
}

private fun taskFor(request: AiRequest): String = when (request) {
    AiRequest.Program ->
        "Suggest a training program for me: one routine per training day, each a list of exercises with target sets and " +
            "reps. Build on my history: keep the lifts I do, base it on what I lift now, and choose the number of days from " +
            "how often I train. With no history, make a balanced three-day full-body program for a beginner."
    AiRequest.NextWorkout ->
        "What should I train today? If a program is active, take today's day: in a fixed program the day whose weekdays " +
            "include today's weekday, in a rotating one the day after last_completed_day (the first if there is none). " +
            "Without an active program, look at my history and routines and pick what is due. For each exercise suggest " +
            "weight and reps per set from my history: a small step up where I hit my target reps last time, the same where " +
            "I did not."
    is AiRequest.FreeText ->
        "During my workout I said this to the app, which did not understand it: " +
            AiContextJson.encodeToString(String.serializer(), request.text) + ". It is speech recognised on my phone, so " +
            "words may be misheard. Turn it into the workout it describes: the exercises I named, in the order I said them, " +
            "with the sets, weights and reps I said. Where I left a number out, take it from my history. If it does not " +
            "describe exercises at all, answer with an empty exercises list."
    AiRequest.Plateaus ->
        "Find where I have stopped progressing. For each exercise I train regularly, follow the best set per session " +
            "(the heaviest weight for the reps, or an estimated one-rep max) over time. Name the exercises with no progress " +
            "for four weeks or more, since when, the numbers that show it, and one concrete change for each (a deload, " +
            "another rep range, a variation, more or less volume) and why."
    is AiRequest.MatchExercises -> error("built by buildAiMatchPrompt")
}
