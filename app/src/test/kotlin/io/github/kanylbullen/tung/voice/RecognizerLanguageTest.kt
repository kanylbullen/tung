package io.github.kanylbullen.tung.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognizerLanguageTest {
    private val pixel = "en-SE-u-fw-mon-ms-metric-mu-celsius"

    @Test
    fun anEnglishPhoneWithASwedishRegionGetsTheVariantInstalledOnIt() {
        // The owner's Pixel, 2026-11-02: Google's recogniser had only en-US on the device and failed
        // on en-SE with error 12.
        val support = RecognizerLanguages(installed = listOf("en-US"), online = listOf("en-US", "en-GB", "sv-SE"))
        assertEquals(
            listOf(RecognizerAttempt("en-US", preferOffline = true), RecognizerAttempt("en-US", preferOffline = false)),
            recognizerAttempts("en", pixel, support),
        )
    }

    @Test
    fun thePhonesOwnVariantWinsWhenTheRecogniserHasIt() {
        val support = RecognizerLanguages(installed = listOf("en-US", "en-GB"), online = listOf("en-GB"))
        assertEquals(RecognizerAttempt("en-GB", preferOffline = true), recognizerAttempts("en", "en-GB", support).first())
    }

    @Test
    fun aLanguageOnlyOnlineIsAskedForOnline() {
        val support = RecognizerLanguages(installed = listOf("en-US"), online = listOf("en-US", "sv-SE"))
        assertEquals(listOf(RecognizerAttempt("sv-SE", preferOffline = false)), recognizerAttempts("sv", "sv-SE", support))
    }

    @Test
    fun withoutAnAnswerThePhonesVariantIsTriedOfflineThenOnlineThenBare() {
        assertEquals(
            listOf(
                RecognizerAttempt("en-SE", preferOffline = true),
                RecognizerAttempt("en-SE", preferOffline = false),
                RecognizerAttempt("en", preferOffline = false),
            ),
            recognizerAttempts("en", pixel, support = null),
        )
    }

    @Test
    fun aRegionOfAnotherLanguageIsNotBorrowed() {
        // A German phone shows the app in English: de-DE's region says nothing about English.
        assertEquals(
            listOf(RecognizerAttempt("en", preferOffline = true), RecognizerAttempt("en", preferOffline = false)),
            recognizerAttempts("en", "de-DE", support = null),
        )
    }

    @Test
    fun anAnswerWithoutTheLanguageFallsBackToTryingAnyway() {
        val support = RecognizerLanguages(installed = listOf("en-US"), online = emptyList())
        assertEquals(RecognizerAttempt("sv-SE", preferOffline = true), recognizerAttempts("sv", "sv_SE", support).first())
    }

    @Test
    fun onlyTheLanguageErrorsMoveOn() {
        assertTrue(isLanguageError(12))
        assertTrue(isLanguageError(13))
        assertFalse(isLanguageError(7))
        assertFalse(isLanguageError(2))
    }
}
