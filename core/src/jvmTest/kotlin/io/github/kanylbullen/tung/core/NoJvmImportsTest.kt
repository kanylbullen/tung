package io.github.kanylbullen.tung.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `core` is what every client shares, so nothing in `core/src/commonMain` may
 * import `android.*`, `androidx.*`, `java.*` or `javax.*`. Compilation alone
 * does not guard this while the JVM is the only target: common sources are
 * then compiled with the JVM's class path, and a `java.time` import compiles
 * until the day an iOS target is added. What needs the JVM goes in
 * `src/jvmMain` (the locale's collator).
 */
class NoJvmImportsTest {

    private val platformImport = Regex("""^\s*import\s+(androidx?|javax?)\.""")

    /** `Dispatchers.IO` is not in common kotlinx-coroutines (JS has none); a caller passes its dispatcher in. */
    private val ioDispatcher = Regex("""\bDispatchers\.IO\b""")

    /**
     * What the JVM offers without an import, so an import scan alone would miss it: a
     * fully qualified `java.util.UUID`, `System.currentTimeMillis()`, `Charsets.UTF_8`,
     * `String.format`, `synchronized`, `Thread`, `Math`, `Character`, `kotlin.jvm.*`.
     */
    private val jvmOnly = Regex("""\b(javax?\.[a-z]|System\.|Charsets\.|String\.format\(|synchronized\(|Thread\.|Math\.|Character\.|kotlin\.jvm\.)""")

    private fun isComment(line: String): Boolean = line.trimStart().let { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }

    private fun commonSources(): File =
        listOfNotNull(System.getProperty("tung.core.commonSrc"), "src/commonMain", "core/src/commonMain")
            .map(::File)
            .firstOrNull { it.isDirectory }
            ?: error("core/src/commonMain not found from ${File(".").absolutePath}")

    @Test
    fun commonMainImportsNothingFromAndroidOrTheJvm() {
        val sources = commonSources().walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
        // An empty scan would pass vacuously; core has over fifty source files today.
        assertTrue("no sources found under ${commonSources()}", sources.size >= 20)
        val offenders = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                when {
                    platformImport.containsMatchIn(line) -> "${file.name}:${i + 1}: ${line.trim()}"
                    isComment(line) -> null
                    ioDispatcher.containsMatchIn(line) -> "${file.name}:${i + 1}: ${line.trim()}"
                    jvmOnly.containsMatchIn(line) -> "${file.name}:${i + 1}: ${line.trim()}"
                    else -> null
                }
            }
        }
        assertEquals("platform imports in core's commonMain", emptyList<String>(), offenders)
    }
}
