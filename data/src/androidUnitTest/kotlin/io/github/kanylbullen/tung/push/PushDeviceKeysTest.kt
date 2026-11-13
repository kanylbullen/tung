package io.github.kanylbullen.tung.push

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Every preference key `PushRegistrar.kt` declares is in [PUSH_DEVICE_KEYS],
 * so a key added later is forgotten after a restore onto another phone too
 * (issue #156). Read from the source, the way `DirtyTrackingTest` reads the DAOs.
 */
class PushDeviceKeysTest {
    @Test
    fun everyPushKeyIsADeviceKey() {
        val path = "src/androidMain/kotlin/io/github/kanylbullen/tung/push/PushRegistrar.kt"
        val source = listOf(File(path), File("data/$path")).first { it.exists() }.readText()
        val declared = Regex("""PreferencesKey\("([a-z_]+)"\)""").findAll(source).map { it.groupValues[1] }.toSet()
        assertEquals(declared, PUSH_DEVICE_KEYS.map { it.name }.toSet())
    }
}
