package io.github.kanylbullen.tung.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two wire formats against recorded responses (`src/jvmTest/resources/ai/`), which only the JVM tests read; the requests are `AiWireTest` in commonTest. */
class AiWireFixturesTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/ai/$name")) { "missing fixture $name" }.readText()

    @Test
    fun aThinkingOnlyAnswerCutAtMaxTokensIsOutOfRoom() {
        assertEquals(AiReply.OutOfRoom, aiReply(AiProviderKind.ANTHROPIC, fixture("anthropic-thinking-max-tokens.json")))
        assertNull(aiResponseText(AiProviderKind.ANTHROPIC, fixture("anthropic-thinking-max-tokens.json")))
    }

    @Test
    fun thinkingBeforeTheTextIsSkippedAndTheTextRead() {
        val reply = aiReply(AiProviderKind.ANTHROPIC, fixture("anthropic-thinking-text.json"))
        val text = (reply as AiReply.Text).text
        assertTrue(text.startsWith("{\"rationale\":\"Squat day"))
        val workout = parseAiAnswer(AiRequest.NextWorkout, text, setOf("back_squat"))
        assertEquals(1, ((workout as AiParse.Parsed).draft as AiDraft.NextWorkout).plan.exercises.size)
    }

    @Test
    fun aRefusalIsDeclined() {
        assertEquals(AiReply.Declined, aiReply(AiProviderKind.ANTHROPIC, fixture("anthropic-refusal.json")))
    }

    @Test
    fun anOpenAiAnswerCutAtTheLengthWithNoContentIsOutOfRoom() {
        assertEquals(AiReply.OutOfRoom, aiReply(AiProviderKind.OPENAI_COMPATIBLE, fixture("openai-length.json")))
        // No text and no reason is still plain nothing.
        assertEquals(AiReply.Empty, aiReply(AiProviderKind.OPENAI_COMPATIBLE, "{\"choices\": []}"))
        assertEquals(AiReply.Empty, aiReply(AiProviderKind.ANTHROPIC, "{\"content\": [], \"stop_reason\": \"end_turn\"}"))
    }

    @Test
    fun responsesAreReadBackToTheModelsText() {
        val openAi = aiResponseText(AiProviderKind.OPENAI_COMPATIBLE, fixture("openai-chat-completion.json"))
        assertTrue(openAi.orEmpty().startsWith("{\"rationale\":\"Two upper"))
        val program = parseAiAnswer(AiRequest.Program, openAi.orEmpty(), setOf("bench_press", "overhead_press", "back_squat"))
        assertEquals(2, ((program as AiParse.Parsed).draft as AiDraft.Program).routines.size)

        val anthropic = aiResponseText(AiProviderKind.ANTHROPIC, fixture("anthropic-message.json"))
        val workout = parseAiAnswer(AiRequest.NextWorkout, anthropic.orEmpty(), setOf("back_squat"))
        assertEquals(1, ((workout as AiParse.Parsed).draft as AiDraft.NextWorkout).plan.exercises.size)

        // The other provider's shape, or no text, reads as nothing.
        assertNull(aiResponseText(AiProviderKind.ANTHROPIC, fixture("openai-chat-completion.json")))
        assertNull(aiResponseText(AiProviderKind.OPENAI_COMPATIBLE, "{\"choices\": []}"))
        assertNull(aiResponseText(AiProviderKind.OPENAI_COMPATIBLE, "not json"))
    }

    @Test
    fun errorMessagesAreReadFromBothProviders() {
        assertTrue(aiErrorMessage(fixture("openai-error.json")).orEmpty().startsWith("Incorrect API key"))
        assertEquals("invalid x-api-key", aiErrorMessage(fixture("anthropic-error.json")))
        assertNull(aiErrorMessage("<html>Bad gateway</html>"))
    }
}
