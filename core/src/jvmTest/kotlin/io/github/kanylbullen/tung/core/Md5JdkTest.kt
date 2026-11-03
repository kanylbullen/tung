package io.github.kanylbullen.tung.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random

/** The pure MD5 and name-based UUID against the JDK's; the RFC 1321 suite is `Md5Test` in commonTest. */
class Md5JdkTest {

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
