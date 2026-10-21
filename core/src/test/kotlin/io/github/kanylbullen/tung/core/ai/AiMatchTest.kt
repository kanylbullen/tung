package io.github.kanylbullen.tung.core.ai

import io.github.kanylbullen.tung.core.CoreCatalog
import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.ExtendedCatalog
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.command.CandidateResolver
import io.github.kanylbullen.tung.core.command.ExerciseCandidate
import io.github.kanylbullen.tung.core.extendedExerciseId
import io.github.kanylbullen.tung.core.importing.importSuggestions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** "Suggest with the assistant" in the import review: what is sent, and the answer read back from both providers. */
class AiMatchTest {

    private fun catalogFile(name: String): File = listOf(File("../catalog/$name"), File("catalog/$name")).first { it.isFile }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/ai/$name")) { "missing fixture $name" }.readText()

    private val core = CoreCatalog.parse(catalogFile("core.json").readText())
    private val extended = catalogFile("extended.json").inputStream().use(ExtendedCatalog::parse)
    private val keys = core.map { it.key }.toSet()

    private val candidates: List<ExerciseCandidate> =
        core.map { ExerciseCandidate(catalogExerciseId(it.key), listOf(it.name.getValue("en")), nameKey = it.key, equipment = Equipment.fromKey(it.equipment)) } +
            extended.map { ExerciseCandidate(extendedExerciseId(it.id), listOf(it.displayName), extendedId = it.id, equipment = Equipment.fromKey(it.equipment)) }

    /** The user's own exercise: a resolver hands it over among the candidates, but it is never sent. */
    private val custom = ExerciseCandidate("custom-1", listOf("Lat pulldown, my gym's machine"), equipment = Equipment.MACHINE)

    private val names = listOf("Machine Lat Pulldown", "Dumbbell Deadlift", "Heel Walking", "Machine Seated Row", "Cable Upper Row")

    private val request = AiRequest.MatchExercises(
        names = names,
        candidates = names.associateWith { importSuggestions(it, candidates + custom) },
        catalogue = aiCatalogue(core),
    )

    private val resolver = CandidateResolver(candidates)

    private fun matches(text: String): AiProposal.Matches {
        val parse = parseAiAnswer(request, text, keys)
        assertTrue("$parse", parse is AiParse.Parsed)
        val resolution = runBlocking { resolveAiAnswer((parse as AiParse.Parsed).draft, resolver) }
        return (resolution as AiResolution.Proposed).proposal as AiProposal.Matches
    }

    @Test
    fun theContextIsOnlyTheNamesTheirCandidatesAndTheCatalogue() {
        val context = buildAiMatchContext(request)
        assertEquals(names, context.names.map { it.name })
        context.names.forEach { n -> assertTrue(n.candidates.size in 1..AI_MATCH_CANDIDATES) }
        assertEquals(core.size, context.catalogue.size)
        assertEquals(AiCatalogueEntry("lat_pulldown", "Lat pulldown", "cable"), context.catalogue.single { it.key == "lat_pulldown" })
        val deadlift = context.names.single { it.name == "Dumbbell Deadlift" }.candidates.first()
        assertEquals(AiMatchCandidate(extendedId = "Stiff-Legged_Dumbbell_Deadlift", name = "Stiff-Legged Dumbbell Deadlift", equipment = "dumbbell"), deadlift)

        val json = Json.parseToJsonElement(AiContextJson.encodeToString(AiMatchContext.serializer(), context)).jsonObject
        assertEquals(setOf("names", "catalogue"), json.keys)
        val candidateFields = json.getValue("names").jsonArray.flatMap { it.jsonObject.getValue("candidates").jsonArray }.flatMap { it.jsonObject.keys }.toSet()
        assertTrue(candidateFields.toString(), setOf("key", "extended_id", "name", "equipment").containsAll(candidateFields))
    }

    @Test
    fun noTrainingDataOrPersonalDataIsSent() {
        val prompt = buildAiMatchPrompt(request)
        listOf(AiProviderKind.OPENAI_COMPATIBLE, AiProviderKind.ANTHROPIC).forEach { provider ->
            val body = aiHttpRequest(AiCall(provider, provider.defaultBaseUrl, "m"), prompt, apiKey = "sk-secret").body
            assertFalse("an @ was sent", body.contains('@'))
            assertFalse(body.contains("sk-secret"))
            listOf("history", "routines", "programs", "weight_kg", "today", "custom-1", "my gym").forEach {
                assertFalse("$it was sent to $provider", body.contains(it))
            }
        }
        // The general builder sends the same, whatever training context it is handed.
        val training = buildAiContext(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC, emptyList(), listOf(AiRoutine("Push day", emptyList())), emptyList(), keys)
        assertEquals(prompt, buildAiPrompt(request, training))
    }

    @Test
    fun thePromptAsksForStrictJsonAndTellsTheLoadRule() {
        val prompt = buildAiMatchPrompt(request, language = "sv")
        assertTrue(prompt.system.contains("exactly one JSON object"))
        assertTrue(prompt.system.contains("\"matches\""))
        assertTrue(prompt.system.contains("Never map across load types"))
        assertTrue(prompt.system.contains("\"sv\""))
        assertTrue(prompt.user.contains("Stiff-Legged_Dumbbell_Deadlift"))
        assertEquals("match_exercises", request.key)
    }

    @Test
    fun anOpenAiAnswerIsValidated() {
        val text = checkNotNull(aiResponseText(AiProviderKind.OPENAI_COMPATIBLE, fixture("openai-match.json")))
        val result = matches(text)
        assertEquals(listOf("Machine Lat Pulldown", "Dumbbell Deadlift", "Heel Walking"), result.matches.map { it.name })
        val pulldown = result.matches[0]
        assertEquals(catalogExerciseId("lat_pulldown"), pulldown.exercise?.id)
        assertEquals(AiConfidence.MEDIUM, pulldown.confidence)
        assertEquals("The catalogue's lat pulldown is on a cable.", pulldown.note)
        assertEquals("Stiff-Legged_Dumbbell_Deadlift", result.matches[1].exercise?.extendedId)
        assertEquals(AiConfidence.HIGH, result.matches[1].confidence)
        // "No good match" is an answer: the row keeps Create and Skip.
        assertNull(result.matches[2].exercise)
        // A made-up key, a made-up extended id, a second answer for a name and a name not asked about.
        assertEquals(4, result.dropped)
    }

    @Test
    fun anAnthropicAnswerInACodeFenceIsReadToo() {
        val text = checkNotNull(aiResponseText(AiProviderKind.ANTHROPIC, fixture("anthropic-match.json")))
        val result = matches(text)
        assertEquals(0, result.dropped)
        // The name is matched the way names are, and the row's own spelling is kept.
        assertEquals("Machine Lat Pulldown", result.matches[0].name)
        assertEquals(AiConfidence.MEDIUM, result.matches[0].confidence)
        assertEquals(AiConfidence.HIGH, result.matches[1].confidence)
        val upper = result.matches.single { it.name == "Cable Upper Row" }
        assertEquals(catalogExerciseId("seated_cable_row"), upper.exercise?.id)
        assertEquals(AiConfidence.LOW, upper.confidence)
    }

    @Test
    fun notTheShapeAskedFor() {
        assertTrue(parseAiAnswer(request, """{"exercises": []}""", keys) is AiParse.Malformed)
        assertTrue(matches("""{"matches": []}""").matches.isEmpty())
    }
}
