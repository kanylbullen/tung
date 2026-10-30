package io.github.kanylbullen.tung.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random

class Md5Test {

    private fun md5Hex(text: String): String = md5(text.encodeToByteArray()).toHex()

    @Test
    fun rfc1321TestSuite() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5Hex(""))
        assertEquals("0cc175b9c0f1b6a831c399e269772661", md5Hex("a"))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", md5Hex("abc"))
        assertEquals("f96b697d7cb7938d525a2f31aaf161d0", md5Hex("message digest"))
        assertEquals("c3fcd3d76192e4007dfb496cca67e13b", md5Hex("abcdefghijklmnopqrstuvwxyz"))
        assertEquals("d174ab98d277d9f5a5611c2c9f419d9f", md5Hex("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"))
        assertEquals("57edf4a22be3c955ac49da2e2107b67a", md5Hex("12345678901234567890123456789012345678901234567890123456789012345678901234567890"))
    }

    @Test
    fun matchesTheJdkAtEveryLengthAroundABlock() {
        val random = Random(42)
        for (length in 0..200) {
            val bytes = ByteArray(length).also(random::nextBytes)
            assertEquals("length $length", MessageDigest.getInstance("MD5").digest(bytes).toHex(), md5(bytes).toHex())
        }
    }

    /** The stored ids were made with `java.util.UUID.nameUUIDFromBytes`; the pure version must give the same. */
    @Test
    fun nameBasedUuidIsWhatTheJdkMakes() {
        val names = listOf("tung:exercise:bench_press", "tung:exercise:ext:Alternate_Incline_Dumbbell_Curl", "tung:import:jefit:workout:7:12", "", "åäö ✓")
        names.forEach { name ->
            assertEquals(name, UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8)).toString(), nameBasedUuid(name))
        }
        val random = Random(7)
        repeat(200) {
            val name = String(CharArray(random.nextInt(0, 40)) { random.nextInt(0x20, 0x250).toChar() })
            assertEquals(name, UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8)).toString(), nameBasedUuid(name))
        }
    }
}
