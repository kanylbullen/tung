package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.web.i18n.English
import io.github.kanylbullen.tung.web.i18n.Swedish
import io.github.kanylbullen.tung.web.i18n.stringsFor
import io.github.kanylbullen.tung.web.ui.durationText
import io.github.kanylbullen.tung.web.ui.lastSyncText
import io.github.kanylbullen.tung.web.ui.shortDate
import io.github.kanylbullen.tung.web.ui.wholeNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StringsTest {

    @Test
    fun swedishForSvAndEnglishForTheRest() {
        assertSame(Swedish, stringsFor("sv"))
        assertSame(Swedish, stringsFor("sv-SE"))
        assertSame(English, stringsFor("en-SE-u-fw-mon"))
        assertSame(English, stringsFor("svk"), "a tag that only starts with the letters is another language")
        assertSame(English, stringsFor("de"))
    }

    @Test
    fun durationsAndCounts() {
        assertEquals("47 min", durationText(47 * 60_000L, English))
        assertEquals("1 h 12 min", durationText(72 * 60_000L, English))
        assertEquals("0 min", durationText(-5L, Swedish))
        assertEquals("1 exercise", English.exerciseCount(1))
        assertEquals("5 övningar", Swedish.exerciseCount(5))
    }

    @Test
    fun theBrowserFormatsDatesAndNumbers() {
        // 2024-03-07 12:00 UTC, a Thursday, looked at in 2026: the year shows.
        // The punctuation between the parts is the browser's ("Thu, 7 Mar
        // 2024" in Chrome's en-GB), so the parts are checked, not the line.
        val march2024 = 1_709_812_800_000L
        val in2026 = 1_777_000_000_000L
        val english = shortDate(march2024, "en-GB", in2026)
        assertTrue(english.startsWith("Thu") && "7" in english && "Mar" in english && english.endsWith("2024"), english)
        val swedish = shortDate(march2024, "sv-SE", in2026)
        assertTrue(swedish.startsWith("Tors") && "7" in swedish && "mars" in swedish && swedish.endsWith("2024"), swedish)
        val thisYear = shortDate(march2024, "en-GB", march2024 + 86_400_000L)
        assertTrue(thisYear.startsWith("Thu") && "2024" !in thisYear, thisYear)
        assertEquals("12,450", wholeNumber(12_450.4, "en-GB"))
        // Swedish groups with a narrow or ordinary no-break space.
        assertEquals("12 450", wholeNumber(12_450.4, "sv-SE").replace('\u00a0', ' ').replace('\u202f', ' '))
    }

    @Test
    fun theSyncStatusLine() {
        val now = 1_777_000_000_000L
        assertEquals("Last sync: never", lastSyncText(null, now, "en-GB", English))
        assertEquals("Synkade nyss", lastSyncText(now - 30_000, now, "sv-SE", Swedish))
        assertEquals("Last sync 12 min ago", lastSyncText(now - 12 * 60_000, now, "en-GB", English))
        assertEquals("Synkade för 3 h sedan", lastSyncText(now - 3 * 3_600_000, now, "sv-SE", Swedish))
    }
}
