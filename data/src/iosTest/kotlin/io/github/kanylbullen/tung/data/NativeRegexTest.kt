package io.github.kanylbullen.tung.data

import io.github.kanylbullen.tung.core.command.normalizeExerciseName
import io.github.kanylbullen.tung.core.voice.VoiceIntent
import io.github.kanylbullen.tung.core.voice.VoiceLanguage
import io.github.kanylbullen.tung.core.voice.parseUtterance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Core's text rules run on Kotlin/Native's own regex engine on the iPhone, which lacks some
 * of the JVM's Unicode classes; a top-level pattern that fails to compile there aborts the
 * app the first time its file is touched (2026-11-03: `\p{N}` in core's `Resolution.kt`
 * crashed the iPhone on "Add exercise"). These run each file's patterns on the simulator.
 */
class NativeRegexTest {

    @Test
    fun exerciseNamesNormalise() {
        assertEquals("incline bench press", normalizeExerciseName("Incline Bench-Press"))
        assertEquals("bankpress 2", normalizeExerciseName("Bänkpress  #2"))
        assertEquals("½ squat", normalizeExerciseName("½ squat"))
    }

    @Test
    fun spokenSetsParse() {
        assertTrue(parseUtterance("80 kilo 8 reps", VoiceLanguage.SWEDISH) !is VoiceIntent.Unknown)
        assertTrue(parseUtterance("eighty kilos eight reps", VoiceLanguage.ENGLISH) !is VoiceIntent.Unknown)
    }
}
