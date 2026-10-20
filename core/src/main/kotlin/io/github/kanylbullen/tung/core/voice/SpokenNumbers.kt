package io.github.kanylbullen.tung.core.voice

/*
 * Numbers as words, in Swedish and English, up to 999: the building blocks
 * ("morphs") of a number word and the one way they combine. A Swedish number
 * is one word ("sjuttiofem", "hundratjugofem"), an English one is several
 * ("one hundred twenty-five", the hyphen dropped while normalising, so
 * "seventyfive"); both go through the same evaluation.
 */

internal enum class MorphKind { ZERO, UNIT, TEEN, TENS, HUNDRED }

/** One building block: 0, 1–9, 10–19, 20–90 or "hundred". */
internal data class Morph(val kind: MorphKind, val value: Int)

private fun zero() = Morph(MorphKind.ZERO, 0)
private fun unit(v: Int) = Morph(MorphKind.UNIT, v)
private fun teen(v: Int) = Morph(MorphKind.TEEN, v)
private fun tens(v: Int) = Morph(MorphKind.TENS, v)
private fun hundred() = Morph(MorphKind.HUNDRED, 100)

internal val SWEDISH_MORPHS: Map<String, Morph> = buildMap {
    put("noll", zero())
    put("en", unit(1)); put("ett", unit(1))
    put("två", unit(2)); put("tre", unit(3)); put("fyra", unit(4)); put("fem", unit(5))
    put("sex", unit(6)); put("sju", unit(7)); put("åtta", unit(8)); put("nio", unit(9))
    put("tio", teen(10)); put("elva", teen(11)); put("tolv", teen(12)); put("tretton", teen(13))
    put("fjorton", teen(14)); put("femton", teen(15)); put("sexton", teen(16)); put("sjutton", teen(17))
    put("arton", teen(18)); put("aderton", teen(18)); put("nitton", teen(19))
    // The written forms and the spoken ones a recogniser may write ("tjugu", "åtti").
    for (w in listOf("tjugo", "tjugu", "tjuge")) put(w, tens(20))
    for (w in listOf("trettio", "tretti")) put(w, tens(30))
    for (w in listOf("fyrtio", "förtio", "fyrti", "förti")) put(w, tens(40))
    for (w in listOf("femtio", "femti")) put(w, tens(50))
    for (w in listOf("sextio", "sexti")) put(w, tens(60))
    for (w in listOf("sjuttio", "sjutti")) put(w, tens(70))
    for (w in listOf("åttio", "åtti")) put(w, tens(80))
    for (w in listOf("nittio", "nitti")) put(w, tens(90))
    put("hundra", hundred())
}

internal val ENGLISH_MORPHS: Map<String, Morph> = buildMap {
    put("zero", zero())
    listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
        .forEachIndexed { i, w -> put(w, unit(i + 1)) }
    listOf("ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
        .forEachIndexed { i, w -> put(w, teen(10 + i)) }
    listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
        .forEachIndexed { i, w -> put(w, tens(20 + 10 * i)) }
    put("fourty", tens(40))
    put("hundred", hundred())
}

/**
 * The value of [morphs] read as one number, or null when they do not make
 * one: an optional hundreds part ("hundred", or 1–9 then "hundred"), then
 * an optional rest (a ten and a unit, a ten, a teen or a unit). Zero only
 * alone. "seventy five" is 75; "five seventy", "twenty hundred" and "seven
 * eight" are not numbers.
 */
internal fun evaluateMorphs(morphs: List<Morph>): Int? {
    if (morphs.isEmpty()) return null
    if (morphs.size == 1 && morphs[0].kind == MorphKind.ZERO) return 0
    var i = 0
    var total = 0
    if (morphs.size >= 2 && morphs[0].kind == MorphKind.UNIT && morphs[1].kind == MorphKind.HUNDRED) {
        total = morphs[0].value * 100
        i = 2
    } else if (morphs[0].kind == MorphKind.HUNDRED) {
        total = 100
        i = 1
    }
    if (i < morphs.size) {
        val m = morphs[i]
        when (m.kind) {
            MorphKind.TENS -> {
                total += m.value
                i++
                if (i < morphs.size && morphs[i].kind == MorphKind.UNIT) total += morphs[i++].value
            }
            MorphKind.TEEN, MorphKind.UNIT -> {
                total += m.value
                i++
            }
            MorphKind.ZERO, MorphKind.HUNDRED -> return null
        }
    }
    return if (i == morphs.size) total else null
}

/**
 * [word] split into morphs that make one number: "sjuttiofem" is 70 + 5,
 * "etthundratjugo" 1 × 100 + 20. Null when no split is a number, or when two
 * splits give different numbers.
 */
internal fun decomposeNumberWord(word: String, morphs: Map<String, Morph>): List<Morph>? {
    val found = mutableListOf<List<Morph>>()
    fun go(at: Int, acc: List<Morph>) {
        if (at == word.length) {
            if (evaluateMorphs(acc) != null) found += acc
            return
        }
        if (acc.size >= MAX_MORPHS) return
        for ((w, m) in morphs) if (word.startsWith(w, at)) go(at + w.length, acc + m)
    }
    go(0, emptyList())
    return found.firstOrNull()?.takeIf { found.map(::evaluateMorphs).toSet().size == 1 }
}

private const val MAX_MORPHS = 5
