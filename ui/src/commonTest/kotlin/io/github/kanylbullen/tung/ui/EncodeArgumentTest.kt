package io.github.kanylbullen.tung.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [encodeArgument] replaced Android's `Uri.encode` for route arguments: an
 * exercise ref (`ext:` ids are free text), the import's source and the
 * Assistant's spoken question. Navigation decodes the argument before the
 * destination reads it, so what matters is RFC 3986 percent-encoding of
 * UTF-8, with the unreserved characters left as they are.
 */
class EncodeArgumentTest {

    @Test
    fun unreservedCharactersAreLeftAlone() {
        val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        assertEquals(unreserved, encodeArgument(unreserved))
    }

    @Test
    fun reservedCharactersAndSpacesArePercentEncoded() {
        assertEquals("a%2Fb%3Fc%3Dd%26e%23f", encodeArgument("a/b?c=d&e#f"))
        assertEquals("100%25%20sure", encodeArgument("100% sure"))
        assertEquals("%2B%3A%40", encodeArgument("+:@"))
    }

    @Test
    fun nonAsciiIsEncodedAsUtf8() {
        assertEquals("B%C3%A4nkpress", encodeArgument("Bänkpress"))
        assertEquals("%C3%A5%C3%A4%C3%B6", encodeArgument("åäö"))
        assertEquals("%F0%9F%92%AA", encodeArgument("💪"))
    }

    @Test
    fun theArgumentsTheAppSendsSurviveARoundTrip() {
        listOf(
            "ext:Barbell_Bench_Press_-_Medium_Grip",
            "ext:weird/id with spaces%and?query",
            "content://com.android.providers.downloads.documents/document/msf%3A1000000033",
            "åttio kilo åtta gånger",
            "",
        ).forEach { value ->
            assertEquals(value, percentDecode(encodeArgument(value)))
        }
    }

    /** RFC 3986 percent-decoding, as navigation applies it to a route argument. */
    private fun percentDecode(encoded: String): String {
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < encoded.length) {
            val c = encoded[i]
            if (c == '%') {
                bytes += encoded.substring(i + 1, i + 3).toInt(16).toByte()
                i += 3
            } else {
                bytes += c.code.toByte()
                i++
            }
        }
        return bytes.toByteArray().decodeToString()
    }
}
