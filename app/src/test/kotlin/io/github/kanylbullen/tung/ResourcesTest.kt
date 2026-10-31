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
 * runs unit tests with `app/` as working directory. The screens in `ui/` have
 * their own strings, as Compose Resources, checked the same way.
 */
class ResourcesTest {

    private fun repoFile(path: String): File =
        listOf(File("../$path"), File(path)).firstOrNull { it.isFile }
            ?: error("$path not found from ${File(".").absolutePath}")

    private fun strings(dir: String): String = repoFile("app/src/main/res/$dir/strings.xml").readText()

    private fun uiStrings(dir: String): String = repoFile("ui/src/commonMain/composeResources/$dir/strings.xml").readText()

    private val element = Regex("""<(string|plurals) name="([^"]+)"[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)

    /** Compose Resources shows quotes as text, so a quote may only be part of a string there, never around it. */
    @Test
    fun noUiStringIsWrappedInQuotes() {
        for (dir in listOf("values", "values-sv")) {
            val quoted = element.findAll(uiStrings(dir)).filter { it.groupValues[3].startsWith("\"") }.map { it.groupValues[2] }
            assertEquals(dir, emptyList<String>(), quoted.toList())
        }
    }

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
    fun theSharedScreensHaveTheSameNamesInSwedish() {
        val english = names(uiStrings("values"))
        assertEquals(english.size, english.toSet().size)
        assertEquals(english.sorted(), names(uiStrings("values-sv")).sorted())
    }

    /**
     * A string lives in one module: a screen in `ui/` owns its strings, and
     * `app/` code that needs one reads it from there (`Res.string.…`), so a
     * wording is never changed in one copy and not the other.
     */
    @Test
    fun aStringLivesInAppOrInUiNotBoth() {
        for (dir in listOf("values", "values-sv")) {
            val both = names(strings(dir)).toSet() intersect names(uiStrings(dir)).toSet()
            assertEquals(dir, emptySet<String>(), both)
        }
    }

    /**
     * No Android escapes in `ui/`: an apostrophe or a quote is written as
     * itself there, since Compose Resources need no `\'` and an escape that
     * Android would undo could show as a backslash on screen.
     */
    @Test
    fun uiStringsCarryNoBackslashEscapes() {
        for (dir in listOf("values", "values-sv")) {
            val escaped = element.findAll(uiStrings(dir)).filter { '\\' in it.groupValues[3] }.map { it.groupValues[2] }
            assertEquals(dir, emptyList<String>(), escaped.toList())
        }
    }

    @Test
    fun catalogueNamesAreEnglishByDefaultAndSwedishInValuesSv() {
        assertEquals(coreNames("en"), exerciseNames(strings("values")))
        assertEquals(coreNames("sv"), exerciseNames(strings("values-sv")))
    }

    /** Both secrets are encrypted under Keystore keys a backup cannot carry, so neither file may be backed up. */
    @Test
    fun theSyncTokenAndTheAssistantKeyAreNeverBackedUp() {
        val legacy = repoFile("app/src/main/res/xml/backup_rules.xml").readText()
        val modern = repoFile("app/src/main/res/xml/data_extraction_rules.xml").readText()
        for (file in listOf("sync_token.xml", "ai_key.xml")) {
            val exclude = """<exclude domain="sharedpref" path="$file" />"""
            assertEquals(file, 1, legacy.split(exclude).size - 1)
            assertEquals(file, 2, modern.split(exclude).size - 1)
        }
    }
}
