package io.github.kanylbullen.tung.wear

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The watch's strings: English by default, Swedish with exactly the same names. */
class WearResourcesTest {

    private fun strings(dir: String): String =
        listOf(File("src/main/res/$dir/strings.xml"), File("wear/src/main/res/$dir/strings.xml"))
            .first { it.isFile }
            .readText()

    private val nameAttr = Regex("""<(?:string|plurals) name="([^"]+)"""")

    private fun names(xml: String): List<String> = nameAttr.findAll(xml).map { it.groupValues[1] }.toList()

    @Test
    fun swedishHasExactlyTheNamesOfTheEnglishDefault() {
        val english = names(strings("values"))
        assertEquals(english.size, english.toSet().size)
        assertEquals(english.sorted(), names(strings("values-sv")).sorted())
    }
}
