package io.github.kanylbullen.tung

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Checks the string resources as files. `values/` is English, the default
 * locale; every translation in `values-<lang>/` carries the same names. Gradle
 * runs unit tests with `app/` as working directory.
 */
class ResourcesTest {

    private fun repoFile(path: String): File =
        listOf(File("../$path"), File(path)).firstOrNull { it.isFile }
            ?: error("$path not found from ${File(".").absolutePath}")

    private fun strings(dir: String): String = repoFile("app/src/main/res/$dir/strings.xml").readText()

    private val nameAttr = Regex("""<(?:string|plurals) name="([^"]+)"""")
    private val exString = Regex("""<string name="ex_([a-z0-9_]+)">(.*?)</string>""")

    private fun names(xml: String): List<String> = nameAttr.findAll(xml).map { it.groupValues[1] }.toList()

    private fun unescape(s: String): String =
        s.replace("\\'", "'").replace("\\\"", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private fun exerciseNames(xml: String): Map<String, String> =
        exString.findAll(xml).associate { it.groupValues[1] to unescape(it.groupValues[2]) }

    private fun coreNames(lang: String): Map<String, String> =
        Json.parseToJsonElement(repoFile("catalog/core.json").readText()).jsonArray.associate { element ->
            val o = element.jsonObject
            o.getValue("key").jsonPrimitive.content to o.getValue("name").jsonObject.getValue(lang).jsonPrimitive.content
        }

    @Test
    fun swedishHasExactlyTheNamesOfTheEnglishDefault() {
        val english = names(strings("values"))
        assertEquals(english.size, english.toSet().size)
        assertEquals(english.sorted(), names(strings("values-sv")).sorted())
    }

    @Test
    fun catalogueNamesAreEnglishByDefaultAndSwedishInValuesSv() {
        assertEquals(coreNames("en"), exerciseNames(strings("values")))
        assertEquals(coreNames("sv"), exerciseNames(strings("values-sv")))
    }
}
