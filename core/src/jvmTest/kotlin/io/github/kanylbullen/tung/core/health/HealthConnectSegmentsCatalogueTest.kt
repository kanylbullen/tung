package io.github.kanylbullen.tung.core.health

import io.github.kanylbullen.tung.core.CoreCatalog
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The mapping against `catalog/core.json` on disk, which only the JVM tests read; the rest is `HealthConnectSegmentsTest` in commonTest. */
class HealthConnectSegmentsCatalogueTest {

    private val catalogueKeys: Set<String> by lazy {
        val file = listOf(File("../catalog/core.json"), File("catalog/core.json")).first { it.isFile }
        CoreCatalog.parse(file.readText()).map { it.key }.toSet()
    }

    @Test
    fun everyMappedKeyIsACatalogueKey() {
        val unknown = HEALTH_CONNECT_SEGMENTS.keys - catalogueKeys
        assertTrue("not in catalog/core.json: $unknown", unknown.isEmpty())
    }
}
