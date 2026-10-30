package io.github.kanylbullen.tung.core.voice

import io.github.kanylbullen.tung.core.MAX_REPS
import io.github.kanylbullen.tung.core.MAX_WEIGHT_KG
import io.github.kanylbullen.tung.core.composeDiacritics
import kotlin.math.floor

/*
 * The deterministic half of voice (docs/architecture.md, "Voice"): one
 * utterance between sets, already text, into one VoiceIntent for the current
 * set. No model, no network, no Android. Anything that is not one of the
 * patterns below, or that the patterns read two ways, is VoiceIntent.Unknown:
 * the grammar never guesses.
 *
 * The patterns, in Swedish and English:
 * - a command phrase, the whole utterance: "samma igen", "nästa", "lägg till
 *   set", "klar", "avsluta passet", "vila" (and their English twins);
 * - an adjustment: "plus"/"minus"/"upp"/"ner" (English "plus"/"minus"/"up"/
 *   "down"/"add") and one number, kilos unless it says reps; "one more rep",
 *   "en rep till";
 * - values: one or two numbers, each with an optional unit. One number needs
 *   a unit ("80 kilo", "8 reps"), or a half to be a weight ("82,5"), since a
 *   bare "80" could be either. Two numbers are weight then reps ("80 8",
 *   "80 kg 8", "80 8 reps", "80 x 8"), or reps then weight when both say so
 *   ("8 reps 80 kilo").
 *
 * Numbers are digits ("80", "82,5", "82.5") or words ("åttio",
 * "sjuttiofem", "two and a half", "åttio komma fem"). Words written apart
 * may be one number or two ("åttio åtta"); every way of reading them is
 * tried, and only an utterance with exactly one meaning is understood.
 * Weights are 0–999 kg in steps of 0.5; reps are 0–999.
 */

/** [text], one utterance in [language], as the intent it asks for. */
fun parseUtterance(text: String, language: VoiceLanguage): VoiceIntent {
    val unknown = VoiceIntent.Unknown(text.trim())
    val vocabulary = when (language) {
        VoiceLanguage.SWEDISH -> SWEDISH
        VoiceLanguage.ENGLISH -> ENGLISH
    }
    val words = vocabulary.stripCourtesy(normalizeUtterance(text))
    if (words.isEmpty()) return unknown
    vocabulary.commands[words.joinToString(" ")]?.let { return it }

    var sign: Int? = null
    var rest = words
    vocabulary.adjustments.firstOrNull { (phrase, _) -> words.startsWith(phrase) }?.let { (phrase, s) ->
        sign = s
        rest = words.drop(phrase.size)
    }
    if (sign == null) {
        vocabulary.oneMore(words)?.let {
            sign = 1
            rest = it
        }
    }
    if (rest.isEmpty()) return unknown

    val tokens = vocabulary.tokenize(rest)
    val meanings = readings(tokens, vocabulary.joiner).mapNotNull { interpret(it, vocabulary, sign) }.toSet()
    return meanings.singleOrNull() ?: unknown
}

/**
 * Lower case, diacritics kept (Swedish words need them), punctuation and
 * symbols to spaces, like `normalizeExerciseName`. A decimal comma or point
 * between digits stays a point ("82,5" → "82.5"), a hyphen between letters
 * goes ("seventy-five" → "seventyfive"), "×" is "x", and digits and letters
 * are split ("80kg" → "80 kg").
 */
internal fun normalizeUtterance(text: String): List<String> {
    var s = composeDiacritics(text).lowercase()
    s = s.replace("×", " x ")
    s = DECIMAL_MARK.replace(s, "#")
    s = WORD_HYPHEN.replace(s, "")
    s = NOT_WORD.replace(s, " ")
    s = DIGIT_LETTER.replace(s, " ")
    return s.replace('#', '.').split(' ').filter { it.isNotEmpty() }
}

private val DECIMAL_MARK = Regex("""(?<=\d)[,.](?=\d)""")
private val WORD_HYPHEN = Regex("""(?<=\p{L})-(?=\p{L})""")
private val NOT_WORD = Regex("""[^\p{L}\p{Nd}#]+""")
private val DIGIT_LETTER = Regex("""(?<=\p{Nd})(?=\p{L})|(?<=\p{L})(?=[\p{Nd}#])""")
private val DIGITS = Regex("""\d+(\.\d+)?""")

// --- tokens ---

private sealed interface Tok {
    /** A number: digits ([morphs] null, never joined to a neighbour) or number words. */
    data class Num(val value: Double, val morphs: List<Morph>?) : Tok

    data class Word(val text: String) : Tok

    /** "och en halv", "and a half": half a kilo more on the number before. */
    data object AddHalf : Tok

    /** "komma fem", "point five": decimals of the number before. */
    data class Decimals(val digits: String) : Tok
}

private class Vocabulary(
    val morphs: Map<String, Morph>,
    val weightUnits: Set<String>,
    val repsUnits: Set<String>,
    /** Words before a number that make it a weight: "vikt 80". */
    val weightWords: Set<String>,
    /** "och"/"and": between two values, or inside a number after "hundred". */
    val joiner: String,
    /** Words that mean decimals follow: "komma", "point". */
    val decimalWords: Set<String>,
    /** Whole phrases → number tokens or [Tok.AddHalf], longest first. */
    phrases: List<Pair<List<String>, Tok>>,
    adjustments: List<Pair<List<String>, Int>>,
    val commands: Map<String, VoiceIntent>,
    private val courtesyBefore: Set<String>,
    private val courtesyAfter: Set<String>,
    /** "one more rep" (the word before the unit), "en rep till" (the word after it). */
    private val moreBefore: String?,
    private val moreAfter: String?,
) {
    val phrases = phrases.sortedByDescending { it.first.size }
    val adjustments = adjustments.sortedByDescending { it.first.size }

    fun stripCourtesy(words: List<String>): List<String> =
        words.dropWhile { it in courtesyBefore }.dropLastWhile { it in courtesyAfter }

    /** "two more reps" → "two reps", "två reps till" → "två reps"; null when it is not that shape. */
    fun oneMore(words: List<String>): List<String>? {
        if (words.size < 3) return null
        if (moreBefore != null && words[words.size - 2] == moreBefore && words.last() in repsUnits) {
            return words.dropLast(2) + words.last()
        }
        if (moreAfter != null && words.last() == moreAfter && words[words.size - 2] in repsUnits) return words.dropLast(1)
        return null
    }

    fun tokenize(words: List<String>): List<Tok> {
        val out = mutableListOf<Tok>()
        var i = 0
        loop@ while (i < words.size) {
            for ((phrase, tok) in phrases) {
                if (words.startsWith(phrase, i)) {
                    out += tok
                    i += phrase.size
                    continue@loop
                }
            }
            val word = words[i]
            if (word in decimalWords) {
                val digits = StringBuilder()
                var j = i + 1
                while (j < words.size) {
                    val w = words[j]
                    val m = morphs[w]
                    when {
                        m != null && (m.kind == MorphKind.UNIT || m.kind == MorphKind.ZERO) -> digits.append(m.value)
                        w.all { it in '0'..'9' } -> digits.append(w)
                        else -> break
                    }
                    j++
                }
                if (digits.isNotEmpty()) {
                    out += Tok.Decimals(digits.toString())
                    i = j
                    continue
                }
            }
            out += when {
                DIGITS.matches(word) -> Tok.Num(word.toDouble(), null)
                else -> decomposeNumberWord(word, morphs)?.let { m -> Tok.Num(evaluateMorphs(m)?.toDouble() ?: 0.0, m) } ?: Tok.Word(word)
            }
            i++
        }
        return out
    }
}

private fun List<String>.startsWith(phrase: List<String>, at: Int = 0): Boolean =
    at + phrase.size <= size && phrase.indices.all { this[at + it] == phrase[it] }

private fun words(s: String): List<String> = s.split(' ')

private fun num(value: Double, morphs: List<Morph>? = null) = Tok.Num(value, morphs)

private val SWEDISH = Vocabulary(
    morphs = SWEDISH_MORPHS,
    weightUnits = setOf("kilo", "kg", "kilogram", "kilon"),
    repsUnits = setOf("reps", "rep", "repetitioner", "repetition", "gånger", "gång"),
    weightWords = setOf("vikt", "vikten"),
    joiner = "och",
    decimalWords = setOf("komma"),
    phrases = listOf(
        words("och en halv") to Tok.AddHalf,
        words("och ett halvt") to Tok.AddHalf,
        words("och halv") to Tok.AddHalf,
        words("och halvt") to Tok.AddHalf,
        words("en halv") to num(0.5),
        words("ett halvt") to num(0.5),
        words("halv") to num(0.5),
        words("halvt") to num(0.5),
    ),
    adjustments = listOf(
        "plus", "upp", "öka", "öka med", "höj", "höj med", "lägg på", "lägg till",
    ).map { words(it) to 1 } + listOf(
        "minus", "ner", "ned", "sänk", "sänk med", "dra av", "ta av",
    ).map { words(it) to -1 },
    commands = buildMap {
        for (p in listOf("samma igen", "samma", "samma som förra", "samma som sist", "en gång till", "igen")) put(p, VoiceIntent.SameAgain)
        for (p in listOf("nästa", "nästa set", "hoppa över")) put(p, VoiceIntent.Next)
        for (p in listOf("lägg till set", "lägg till ett set", "lägg till ett till set", "ett set till", "nytt set")) put(p, VoiceIntent.AddSet)
        for (p in listOf("klar", "klart", "färdig", "färdigt", "logga", "logga det", "logga set", "logga setet")) put(p, VoiceIntent.Done)
        for (p in listOf("avsluta passet", "avsluta pass", "avsluta träningen", "avsluta träningspasset", "avsluta")) put(p, VoiceIntent.Finish)
        for (p in listOf("vila", "starta vila", "starta vilan")) put(p, VoiceIntent.Rest)
    },
    courtesyBefore = setOf("ok", "okej", "okay"),
    courtesyAfter = setOf("tack"),
    moreBefore = null,
    moreAfter = "till",
)

private val ENGLISH = Vocabulary(
    morphs = ENGLISH_MORPHS,
    weightUnits = setOf("kilo", "kilos", "kg", "kgs", "kilogram", "kilograms", "kilogramme", "kilogrammes"),
    repsUnits = setOf("reps", "rep", "repetitions", "repetition", "times", "time"),
    weightWords = setOf("weight"),
    joiner = "and",
    decimalWords = setOf("point"),
    phrases = listOf(
        words("and a half") to Tok.AddHalf,
        words("and half") to Tok.AddHalf,
        words("a half") to num(0.5),
        words("one half") to num(0.5),
        words("half") to num(0.5),
        // "a hundred" is "one hundred": 1 × 100, which may take a rest ("a hundred twenty").
        words("a hundred") to num(100.0, listOf(Morph(MorphKind.UNIT, 1), Morph(MorphKind.HUNDRED, 100))),
    ),
    adjustments = listOf(
        "plus", "up", "up by", "add", "increase", "increase by", "go up", "go up by",
    ).map { words(it) to 1 } + listOf(
        "minus", "down", "down by", "decrease", "decrease by", "drop", "drop by", "go down", "go down by", "take off",
    ).map { words(it) to -1 },
    commands = buildMap {
        for (p in listOf("same again", "same", "same as last", "same as last time", "again", "one more time")) put(p, VoiceIntent.SameAgain)
        for (p in listOf("next", "next set")) put(p, VoiceIntent.Next)
        for (p in listOf("add set", "add a set", "add another set", "another set", "new set", "one more set")) put(p, VoiceIntent.AddSet)
        for (p in listOf("done", "log", "log it", "log set", "log the set", "set done")) put(p, VoiceIntent.Done)
        for (p in listOf("finish workout", "finish the workout", "end workout", "end the workout", "finish")) put(p, VoiceIntent.Finish)
        for (p in listOf("rest", "start rest", "start the rest", "start rest timer", "start the rest timer")) put(p, VoiceIntent.Rest)
    },
    courtesyBefore = setOf("ok", "okay"),
    courtesyAfter = setOf("please", "thanks"),
    moreBefore = "more",
    moreAfter = null,
)

// --- readings: which number words written apart are one number ---

/** Number tokens [from] and [to] may be one number; [mandatory] when the second is "hundred" ("five hundred"). */
private data class Link(val from: Int, val to: Int, val mandatory: Boolean)

private const val MAX_OPTIONAL_LINKS = 6

/**
 * Every way of reading [tokens] with number words written apart joined or
 * not: "hundra tjugo fem" is read as 125, 120 and 5, 100 and 25, and 100, 20
 * and 5. A join must make a number ([evaluateMorphs]); "five hundred" is
 * always joined, and an English "and" after "hundred" may join ("a hundred
 * and five") or separate.
 */
private fun readings(tokens: List<Tok>, joiner: String): List<List<Tok>> {
    val links = mutableListOf<Link>()
    tokens.forEachIndexed { i, t ->
        val a = (t as? Tok.Num)?.morphs ?: return@forEachIndexed
        when (val next = tokens.getOrNull(i + 1)) {
            is Tok.Num -> {
                val b = next.morphs
                if (b != null && evaluateMorphs(a + b) != null) links += Link(i, i + 1, mandatory = b.first().kind == MorphKind.HUNDRED)
            }
            is Tok.Word -> if (next.text == joiner && a.last().kind == MorphKind.HUNDRED) {
                val b = (tokens.getOrNull(i + 2) as? Tok.Num)?.morphs
                if (b != null && b.first().kind != MorphKind.HUNDRED && evaluateMorphs(a + b) != null) links += Link(i, i + 2, mandatory = false)
            }
            else -> Unit
        }
    }
    val optional = links.filterNot { it.mandatory }
    if (optional.size > MAX_OPTIONAL_LINKS) return emptyList()
    return (0 until (1 shl optional.size)).mapNotNull { mask ->
        val chosen = links.filter { it.mandatory || (mask shr optional.indexOf(it)) and 1 == 1 }.associateBy { it.from }
        joined(tokens, chosen)
    }
}

/** [tokens] with the [links] joined into single numbers; null when a joined run is not a number. */
private fun joined(tokens: List<Tok>, links: Map<Int, Link>): List<Tok>? {
    val out = mutableListOf<Tok>()
    var i = 0
    while (i < tokens.size) {
        val t = tokens[i]
        val link = links[i]
        if (t is Tok.Num && link != null) {
            var morphs = t.morphs.orEmpty()
            var j = i
            while (true) {
                val l = links[j] ?: break
                morphs = morphs + (tokens[l.to] as? Tok.Num)?.morphs.orEmpty()
                j = l.to
            }
            val value = evaluateMorphs(morphs) ?: return null
            out += Tok.Num(value.toDouble(), morphs)
            i = j + 1
        } else {
            out += t
            i++
        }
    }
    return out
}

// --- one reading → one intent ---

private enum class Measure { WEIGHT, REPS }

private data class Value(val number: Double, val fraction: Boolean)

private sealed interface Elem {
    data class Number(val value: Value) : Elem
    data class Of(val unit: Measure) : Elem
    data object WeightWord : Elem
    data object Times : Elem
}

private data class Group(val value: Value, val unit: Measure?)

/** One reading as an intent, or null when it is not one of the patterns. [sign] is set after "plus"/"minus". */
private fun interpret(tokens: List<Tok>, vocabulary: Vocabulary, sign: Int?): VoiceIntent? {
    val elems = mutableListOf<Elem>()
    for (t in tokens) {
        when (t) {
            is Tok.Num -> elems += Elem.Number(Value(t.value, fraction = t.value != floor(t.value)))
            Tok.AddHalf -> {
                val last = elems.lastOrNull() as? Elem.Number ?: return null
                if (last.value.fraction) return null
                elems[elems.size - 1] = Elem.Number(Value(last.value.number + 0.5, fraction = true))
            }
            is Tok.Decimals -> {
                val last = elems.lastOrNull() as? Elem.Number ?: return null
                if (last.value.fraction) return null
                val number = "${last.value.number.toLong()}.${t.digits}".toDoubleOrNull() ?: return null
                elems[elems.size - 1] = Elem.Number(Value(number, fraction = true))
            }
            is Tok.Word -> when (t.text) {
                in vocabulary.weightUnits -> elems += Elem.Of(Measure.WEIGHT)
                in vocabulary.repsUnits -> elems += Elem.Of(Measure.REPS)
                in vocabulary.weightWords -> elems += Elem.WeightWord
                "x" -> elems += Elem.Times
                vocabulary.joiner -> Unit
                else -> return null
            }
        }
    }

    val groups = mutableListOf<Group>()
    var times = false
    var i = 0
    while (i < elems.size) {
        when (val e = elems[i]) {
            is Elem.Number -> {
                val unit = (elems.getOrNull(i + 1) as? Elem.Of)?.unit
                groups += Group(e.value, unit)
                i += if (unit != null) 2 else 1
            }
            Elem.WeightWord -> {
                val n = elems.getOrNull(i + 1) as? Elem.Number ?: return null
                val after = (elems.getOrNull(i + 2) as? Elem.Of)?.unit
                if (after == Measure.REPS) return null
                groups += Group(n.value, Measure.WEIGHT)
                i += if (after == Measure.WEIGHT) 3 else 2
            }
            Elem.Times -> {
                if (times || groups.size != 1) return null
                times = true
                i++
            }
            is Elem.Of -> return null
        }
    }
    if (times && groups.size != 2) return null

    if (sign != null) {
        val g = groups.singleOrNull() ?: return null
        return when (g.unit) {
            Measure.REPS -> reps(g.value)?.takeIf { it > 0 }?.let { VoiceIntent.AdjustReps(sign * it) }
            Measure.WEIGHT, null -> weight(g.value)?.takeIf { it > 0.0 }?.let { VoiceIntent.AdjustWeight(sign * it) }
        }
    }

    return when (groups.size) {
        1 -> {
            val g = groups[0]
            when (g.unit) {
                Measure.WEIGHT -> weight(g.value)?.let { VoiceIntent.SetWeightAndReps(it, null) }
                Measure.REPS -> reps(g.value)?.let { VoiceIntent.SetWeightAndReps(null, it) }
                // A bare whole number could be either; a half can only be a weight.
                null -> if (g.value.fraction) weight(g.value)?.let { VoiceIntent.SetWeightAndReps(it, null) } else null
            }
        }
        2 -> {
            val (a, b) = groups
            val (w, r) = when (a.unit to b.unit) {
                Measure.WEIGHT to Measure.REPS, Measure.WEIGHT to null, null to Measure.REPS, null to null -> a to b
                Measure.REPS to Measure.WEIGHT -> b to a
                else -> return null
            }
            val kg = weight(w.value) ?: return null
            val count = reps(r.value) ?: return null
            VoiceIntent.SetWeightAndReps(kg, count)
        }
        else -> null
    }
}

/** A weight the app takes: 0–999 kg in steps of 0.5. */
private fun weight(v: Value): Double? = v.number.takeIf { it in 0.0..MAX_WEIGHT_KG && it * 2 == floor(it * 2) }

/** A rep count: whole, 0–999. */
private fun reps(v: Value): Int? = v.number.takeIf { !v.fraction && it in 0.0..MAX_REPS.toDouble() }?.toInt()
