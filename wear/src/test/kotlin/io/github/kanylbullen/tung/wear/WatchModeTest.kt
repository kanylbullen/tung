package io.github.kanylbullen.tung.wear

import io.github.kanylbullen.tung.core.sync.ServerUrlCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The watch's mode and the pure parts of linking (issue #30). */
class WatchModeTest {

    @Test
    fun aTokenMakesTheWatchStandaloneAndNoTokenACompanion() {
        assertEquals(WatchMode.STANDALONE, watchMode(hasToken = true))
        assertEquals(WatchMode.COMPANION, watchMode(hasToken = false))
    }

    @Test
    fun theNumberPadTakesSixDigitsAndNothingElse() {
        var input = LinkCodeInput()
        "04a2-9 17 3".forEach { input = input.type(it) }
        assertEquals("042917", input.digits)
        assertTrue(input.isComplete)
        assertEquals("042917", input.type('5').digits)

        val shorter = input.delete()
        assertEquals("04291", shorter.digits)
        assertFalse(shorter.isComplete)
        assertEquals("", LinkCodeInput().delete().digits)
    }

    @Test(expected = IllegalArgumentException::class)
    fun anInputCannotHoldMoreThanACode() {
        LinkCodeInput("1234567")
    }

    @Test
    fun aCodeIsSixDigitsWithOptionalSpacesOrDashes() {
        assertEquals("042917", LinkCodeInput.parse("042917"))
        assertEquals("042917", LinkCodeInput.parse("042 917"))
        assertEquals("042917", LinkCodeInput.parse("042-917"))
        assertNull(LinkCodeInput.parse("04291"))
        assertNull(LinkCodeInput.parse("0429170"))
        assertNull(LinkCodeInput.parse("04291a"))
        assertNull(LinkCodeInput.parse(""))
    }

    @Test
    fun aTypedHostMeansHttpsAndHttpOnlyLocally() {
        assertEquals(ServerUrlCheck.Valid("https://tung.example.org"), checkWatchServerInput(" tung.example.org/ "))
        assertEquals(ServerUrlCheck.Valid("http://192.168.1.10:8080"), checkWatchServerInput("http://192.168.1.10:8080"))
        assertEquals(ServerUrlCheck.NeedsHttps, checkWatchServerInput("http://tung.example.org"))
        assertEquals(ServerUrlCheck.Malformed, checkWatchServerInput(""))
    }

    @Test
    fun thePhonesServerComesFirstAndNothingTwice() {
        assertEquals(
            listOf("https://phone.example", "https://old.example"),
            serverChoices("https://phone.example", listOf("https://phone.example", "https://old.example")),
        )
        assertEquals(listOf("https://old.example"), serverChoices(null, listOf("https://old.example")))
        assertEquals(emptyList<String>(), serverChoices(null, emptyList()))
    }
}
