package io.github.kanylbullen.tung.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `core` must stay usable from a server and a watch app, so nothing in
 * `core/src/main` may import `android.*` or `androidx.*`. Compilation alone does
 * not guard this: the import would compile the day someone adds an Android
 * dependency to `core/build.gradle.kts`.
 */
class NoAndroidImportsTest {

    private val androidImport = Regex("""^\s*import\s+androidx?\.""")

    private fun mainSources(): File =
        listOfNotNull(System.getProperty("tung.core.mainSrc"), "src/main", "core/src/main")
            .map(::File)
            .firstOrNull { it.isDirectory }
            ?: error("core/src/main not found from ${File(".").absolutePath}")

    @Test
    fun coreMainHasNoAndroidImports() {
        val sources = mainSources().walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
        // An empty scan would pass vacuously; core has nine source files today.
        assertTrue("no sources found under ${mainSources()}", sources.size >= 5)
        val offenders = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                if (androidImport.containsMatchIn(line)) "${file.name}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertEquals("android/androidx imports in core", emptyList<String>(), offenders)
    }
}
