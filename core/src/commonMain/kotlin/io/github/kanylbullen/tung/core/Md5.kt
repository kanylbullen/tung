package io.github.kanylbullen.tung.core

/**
 * MD5 (RFC 1321) in plain Kotlin, for the name-based UUIDs ([catalogExerciseId],
 * `importId`): those ids are stored on every device and on the server, so each
 * platform must compute exactly what `java.util.UUID.nameUUIDFromBytes` did.
 * `Md5Test` checks the RFC's vectors and the JDK's UUIDs. Not for anything
 * that needs a secure hash.
 */
internal fun md5(message: ByteArray): ByteArray {
    val padded = ByteArray(((message.size + 8) / 64 + 1) * 64)
    message.copyInto(padded)
    padded[message.size] = 0x80.toByte()
    val bitLength = message.size.toLong() * 8
    for (i in 0 until 8) padded[padded.size - 8 + i] = (bitLength ushr (8 * i)).toByte()

    var a0 = 0x67452301
    var b0 = 0xefcdab89.toInt()
    var c0 = 0x98badcfe.toInt()
    var d0 = 0x10325476
    val m = IntArray(16)
    for (chunk in padded.indices step 64) {
        for (i in 0 until 16) {
            val o = chunk + i * 4
            m[i] = (padded[o].toInt() and 0xff) or
                ((padded[o + 1].toInt() and 0xff) shl 8) or
                ((padded[o + 2].toInt() and 0xff) shl 16) or
                ((padded[o + 3].toInt() and 0xff) shl 24)
        }
        var a = a0
        var b = b0
        var c = c0
        var d = d0
        for (i in 0 until 64) {
            val f: Int
            val g: Int
            when (i / 16) {
                0 -> { f = (b and c) or (b.inv() and d); g = i }
                1 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) % 16 }
                2 -> { f = b xor c xor d; g = (3 * i + 5) % 16 }
                else -> { f = c xor (b or d.inv()); g = (7 * i) % 16 }
            }
            val next = b + (a + f + K[i] + m[g]).rotateLeft(S[i])
            a = d
            d = c
            c = b
            b = next
        }
        a0 += a
        b0 += b
        c0 += c
        d0 += d
    }
    val out = ByteArray(16)
    for ((j, word) in intArrayOf(a0, b0, c0, d0).withIndex()) {
        for (i in 0 until 4) out[j * 4 + i] = (word ushr (8 * i)).toByte()
    }
    return out
}

/** Per-round shift amounts. */
private val S = intArrayOf(
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
)

/** floor(abs(sin(i + 1)) × 2³²), as the RFC lists them; a table rather than `sin`, which may round differently per platform. */
private val K: IntArray = longArrayOf(
    0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
    0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
    0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
    0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
    0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
    0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
    0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
    0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391,
).let { table -> IntArray(64) { table[it].toInt() } }

private const val HEX = "0123456789abcdef"

/** [bytes] as lowercase hex. */
internal fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
    }
    return out.toString()
}
