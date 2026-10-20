package io.github.kanylbullen.tung.core.voice

import io.github.kanylbullen.tung.core.voice.VoiceIntent.AddSet
import io.github.kanylbullen.tung.core.voice.VoiceIntent.AdjustReps
import io.github.kanylbullen.tung.core.voice.VoiceIntent.AdjustWeight
import io.github.kanylbullen.tung.core.voice.VoiceIntent.Done
import io.github.kanylbullen.tung.core.voice.VoiceIntent.Finish
import io.github.kanylbullen.tung.core.voice.VoiceIntent.Next
import io.github.kanylbullen.tung.core.voice.VoiceIntent.Rest
import io.github.kanylbullen.tung.core.voice.VoiceIntent.SameAgain
import io.github.kanylbullen.tung.core.voice.VoiceIntent.SetWeightAndReps
import io.github.kanylbullen.tung.core.voice.VoiceIntent.Unknown
import io.github.kanylbullen.tung.core.voice.VoiceLanguage.ENGLISH
import io.github.kanylbullen.tung.core.voice.VoiceLanguage.SWEDISH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceGrammarTest {

    private data class Case(val language: VoiceLanguage, val text: String, val expected: VoiceIntent)

    private fun sv(text: String, expected: VoiceIntent) = Case(SWEDISH, text, expected)
    private fun en(text: String, expected: VoiceIntent) = Case(ENGLISH, text, expected)
    private fun kgReps(kg: Double?, reps: Int?) = SetWeightAndReps(kg, reps)
    private fun kg(kg: Double) = SetWeightAndReps(kg, null)
    private fun reps(reps: Int) = SetWeightAndReps(null, reps)

    /** What was heard, back as [Unknown]: nothing is guessed. */
    private fun unknown(text: String) = Unknown(text)

    private val table = listOf(
        // --- Swedish: values ---
        sv("åttio kilo åtta reps", kgReps(80.0, 8)),
        sv("Åttio kilo, åtta reps.", kgReps(80.0, 8)),
        sv("80 kg 8", kgReps(80.0, 8)),
        sv("80 8", kgReps(80.0, 8)),
        sv("80 8 reps", kgReps(80.0, 8)),
        sv("80 x 8", kgReps(80.0, 8)),
        sv("80×8", kgReps(80.0, 8)),
        sv("80kg 8reps", kgReps(80.0, 8)),
        sv("8 reps 80 kilo", kgReps(80.0, 8)),
        sv("åttio kilo och åtta reps", kgReps(80.0, 8)),
        sv("8 reps", reps(8)),
        sv("åtta repetitioner", reps(8)),
        sv("tio gånger", reps(10)),
        sv("noll reps", reps(0)),
        sv("sjuttiofem kilo", kg(75.0)),
        sv("sjuttio fem kilo", kg(75.0)),
        sv("82,5", kg(82.5)),
        sv("82.5 kg", kg(82.5)),
        sv("åttiotvå och en halv kilo", kg(82.5)),
        sv("två och en halv kilo", kg(2.5)),
        sv("åttio komma fem kilo", kg(80.5)),
        sv("hundratjugo kilo fem reps", kgReps(120.0, 5)),
        sv("hundra tjugo kilo", kg(120.0)),
        sv("etthundra kilo", kg(100.0)),
        sv("vikt hundra", kg(100.0)),
        sv("noll kilo", kg(0.0)),
        // Two number words written apart: 88 alone means nothing, so it is 80 then 8.
        sv("åttio åtta", kgReps(80.0, 8)),
        // --- Swedish: adjustments ---
        sv("plus fem", AdjustWeight(5.0)),
        sv("minus fem", AdjustWeight(-5.0)),
        sv("plus två och en halv", AdjustWeight(2.5)),
        sv("upp två och en halv kilo", AdjustWeight(2.5)),
        sv("ner tio", AdjustWeight(-10.0)),
        sv("plus en halv", AdjustWeight(0.5)),
        sv("plus en rep", AdjustReps(1)),
        sv("minus två reps", AdjustReps(-2)),
        sv("en rep till", AdjustReps(1)),
        // --- Swedish: commands ---
        sv("samma igen", SameAgain),
        sv("Samma igen!", SameAgain),
        sv("nästa", Next),
        sv("nästa set", Next),
        sv("lägg till set", AddSet),
        sv("lägg till ett set", AddSet),
        sv("klar", Done),
        sv("logga", Done),
        sv("ok klar tack", Done),
        sv("avsluta passet", Finish),
        sv("vila", Rest),
        // --- Swedish: not understood ---
        sv("80", unknown("80")),
        sv("åttio", unknown("åttio")),
        sv("åttio åtta reps", unknown("åttio åtta reps")), // 88 reps, or 80 × 8
        sv("82,25 kg", unknown("82,25 kg")), // not a half-kilo step
        sv("1000 kg", unknown("1000 kg")),
        sv("8,5 reps", unknown("8,5 reps")),
        sv("8 kilo 80 kilo", unknown("8 kilo 80 kilo")),
        sv("80 8 kilo", unknown("80 8 kilo")),
        sv("80 8 10", unknown("80 8 10")),
        sv("åttio något", unknown("åttio något")),
        sv("plus", unknown("plus")),
        sv("plus noll", unknown("plus noll")),
        sv("eighty kilos eight reps", unknown("eighty kilos eight reps")),
        sv("  ", unknown("")),

        // --- English: values ---
        en("eighty kilos eight reps", kgReps(80.0, 8)),
        en("Eighty kilos, eight reps", kgReps(80.0, 8)),
        en("80 kg 8", kgReps(80.0, 8)),
        en("8 reps 80 kg", kgReps(80.0, 8)),
        en("80 x 8", kgReps(80.0, 8)),
        en("seventy-five kilos", kg(75.0)),
        en("seventy five kilos", kg(75.0)),
        en("eight reps", reps(8)),
        en("8 times", reps(8)),
        en("zero reps", reps(0)),
        en("two and a half kilos", kg(2.5)),
        en("eighty two and a half kilos", kg(82.5)),
        en("eighty point five kilos", kg(80.5)),
        en("82.5", kg(82.5)),
        en("a hundred kilos", kg(100.0)),
        en("one hundred and twenty kilos", kg(120.0)),
        en("weight sixty", kg(60.0)),
        // --- English: adjustments ---
        en("up five", AdjustWeight(5.0)),
        en("down five", AdjustWeight(-5.0)),
        en("plus five", AdjustWeight(5.0)),
        en("up two and a half", AdjustWeight(2.5)),
        en("add 2.5", AdjustWeight(2.5)),
        en("minus two reps", AdjustReps(-2)),
        en("one more rep", AdjustReps(1)),
        en("two more reps", AdjustReps(2)),
        // --- English: commands ---
        en("same again", SameAgain),
        en("next", Next),
        en("next set", Next),
        en("add a set", AddSet),
        en("done", Done),
        en("log it", Done),
        en("done please", Done),
        en("finish workout", Finish),
        en("rest", Rest),
        // --- English: not understood ---
        en("eighty", unknown("eighty")),
        en("eighty something", unknown("eighty something")),
        en("eighty eight reps", unknown("eighty eight reps")), // 88 reps, or 80 × 8
        en("one hundred twenty-five reps", unknown("one hundred twenty-five reps")), // 125 reps, or 100 × 25
        en("a hundred and eight reps", unknown("a hundred and eight reps")),
        en("80 times 8", unknown("80 times 8")),
        en("åttio kilo åtta reps", unknown("åttio kilo åtta reps")),
        en("down", unknown("down")),
    )

    @Test
    fun theTable() {
        assertTrue("at least 60 utterances", table.size >= 60)
        val failures = table.mapNotNull { c ->
            val actual = parseUtterance(c.text, c.language)
            if (actual == c.expected) null else "${c.language} \"${c.text}\": expected ${c.expected}, got $actual"
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    // --- every spoken number 0–200 ---

    private val svUnits = listOf("", "ett", "två", "tre", "fyra", "fem", "sex", "sju", "åtta", "nio")
    private val svTeens = listOf("tio", "elva", "tolv", "tretton", "fjorton", "femton", "sexton", "sjutton", "arton", "nitton")
    private val svTens = listOf("", "", "tjugo", "trettio", "fyrtio", "femtio", "sextio", "sjuttio", "åttio", "nittio")

    /** 1–99 as words, each a list of the words a recogniser might write apart. */
    private fun svBelow100(n: Int): List<String> = when {
        n < 10 -> listOf(svUnits[n])
        n < 20 -> listOf(svTeens[n - 10])
        n % 10 == 0 -> listOf(svTens[n / 10])
        else -> listOf(svTens[n / 10], svUnits[n % 10])
    }

    /** n as Swedish words: written as one word, with "etthundra", and with the parts apart. */
    private fun swedish(n: Int): List<String> {
        if (n == 0) return listOf("noll")
        if (n == 200) return listOf("tvåhundra", "två hundra")
        if (n < 100) return listOf(svBelow100(n).joinToString(""), svBelow100(n).joinToString(" "))
        val rest = n - 100
        val parts = if (rest == 0) emptyList() else svBelow100(rest)
        return listOf(
            "hundra" + parts.joinToString(""),
            "etthundra" + parts.joinToString(""),
            (listOf("hundra") + parts).joinToString(" "),
        )
    }

    private val enUnits = listOf("", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    private val enTeens = listOf("ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val enTens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    private fun enBelow100(n: Int): String = when {
        n < 10 -> enUnits[n]
        n < 20 -> enTeens[n - 10]
        n % 10 == 0 -> enTens[n / 10]
        else -> "${enTens[n / 10]}-${enUnits[n % 10]}"
    }

    /** n as English words: "one hundred twenty-five", "a hundred twenty-five", "one hundred and twenty-five". */
    private fun english(n: Int): List<String> = when {
        n == 0 -> listOf("zero")
        n < 100 -> listOf(enBelow100(n), enBelow100(n).replace('-', ' '))
        n == 100 -> listOf("one hundred", "a hundred", "hundred")
        n == 200 -> listOf("two hundred")
        else -> enBelow100(n - 100).let { listOf("one hundred $it", "a hundred $it", "one hundred and $it") }
    }

    @Test
    fun everySpokenNumberUpTo200IsAWeight() {
        val failures = (0..200).flatMap { n ->
            swedish(n).map { Triple(SWEDISH, "$it kilo", n) } + english(n).map { Triple(ENGLISH, "$it kilos", n) }
        }.mapNotNull { (language, text, n) ->
            val actual = parseUtterance(text, language)
            if (actual == kg(n.toDouble())) null else "$language \"$text\": got $actual"
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    @Test
    fun everySpokenNumberUpTo200IsAnAdjustment() {
        val failures = (1..200).flatMap { n ->
            (swedish(n).map { Triple(SWEDISH, "plus $it", n) } + english(n).map { Triple(ENGLISH, "up $it", n) })
        }.mapNotNull { (language, text, n) ->
            val actual = parseUtterance(text, language)
            if (actual == AdjustWeight(n.toDouble())) null else "$language \"$text\": got $actual"
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    /**
     * Reps up to 200 in Swedish, written as one word as Swedish writes them.
     * In English only below 100: "one hundred twenty-five reps" is also
     * 100 kg × 25 reps, so it is not understood (see the table).
     */
    @Test
    fun spokenRepCounts() {
        val cases = (0..200).map { n -> Triple(SWEDISH, "${swedish(n).first()} reps", n) } +
            (0..99).map { n -> Triple(ENGLISH, "${english(n).first()} reps", n) }
        val failures = cases.mapNotNull { (language, text, n) ->
            val actual = parseUtterance(text, language)
            if (actual == reps(n)) null else "$language \"$text\": got $actual"
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    @Test
    fun digitsForEveryWeightStepAndRepCount() {
        for (half in 0..1998) {
            val kg = half / 2.0
            val written = if (half % 2 == 0) "${half / 2}" else "${half / 2},5"
            assertEquals(written, kg(kg), parseUtterance("$written kg", SWEDISH))
        }
        for (r in 0..999) assertEquals(reps(r), parseUtterance("$r reps", ENGLISH))
    }

    @Test
    fun languageFromTheAppLocale() {
        assertEquals(SWEDISH, VoiceLanguage.of("sv"))
        assertEquals(SWEDISH, VoiceLanguage.of("sv-SE"))
        assertEquals(ENGLISH, VoiceLanguage.of("en-GB"))
        assertEquals(ENGLISH, VoiceLanguage.of("de"))
    }

    @Test
    fun normalisation() {
        assertEquals(listOf("82.5", "kg", "8", "reps"), normalizeUtterance("82,5kg; 8 reps!"))
        assertEquals(listOf("seventyfive", "kilos"), normalizeUtterance("Seventy-five kilos."))
        assertEquals(listOf("åttio", "x", "8"), normalizeUtterance("Åttio×8"))
    }
}
