package io.github.kanylbullen.tung.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #75: the model is told a language by name, from the language subtag only. */
class AiLanguageTest {
    @Test
    fun onlyTheLanguageSubtagCounts() {
        assertEquals("English", aiLanguageName("en-SE-u-fw-mon-ms-metric-mu-celsius"))
        assertEquals("English", aiLanguageName("en"))
        assertEquals("Swedish", aiLanguageName("sv-SE"))
        assertEquals("Swedish", aiLanguageName("sv"))
        assertEquals("Swedish", aiLanguageName("SV_se"))
    }

    /** The app ships English and Swedish; any other language shows the English resources, so the model writes English. */
    @Test
    fun anythingElseIsEnglish() {
        assertEquals("English", aiLanguageName("de-DE"))
        assertEquals("English", aiLanguageName(""))
        assertEquals("English", aiLanguageName("und"))
    }

    /** Every request type names the language, never the tag: a region in it cannot reach the model. */
    @Test
    fun everyPromptNamesTheLanguageAndNotTheTag() {
        val tag = "en-SE-u-fw-mon-ms-metric-mu-celsius"
        val context = buildAiContext(0L, kotlinx.datetime.TimeZone.UTC, emptyList(), emptyList(), emptyList(), listOf("bench_press"))
        val requests = listOf(
            AiRequest.Program,
            AiRequest.NextWorkout,
            AiRequest.Plateaus,
            AiRequest.FreeText("bench"),
            AiRequest.MatchExercises(listOf("Bench"), emptyMap(), emptyList()),
        )
        for (request in requests) {
            val prompt = buildAiPrompt(request, context, language = tag)
            assertTrue("${request.key} names English", prompt.system.contains("in English."))
            assertFalse("${request.key} carries the tag", (prompt.system + prompt.user).contains("en-SE"))
            assertFalse("${request.key} mentions Swedish", prompt.system.contains("Swedish"))
        }
    }
}
