package io.github.kanylbullen.tung.data

import io.github.kanylbullen.tung.core.CoreExercise
import io.github.kanylbullen.tung.core.ExtendedCatalog
import io.github.kanylbullen.tung.core.catalog.CatalogueIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Core's [CatalogueIndex] over the catalogue files the APK ships, for the
 * exercise page: what each catalogue exercise is (muscles, equipment,
 * instructions). Like [CatalogueMuscles], built once on IO the first time
 * [index] is called, and with the core entries only if the extended
 * catalogue cannot be read. Nothing of it is in Room.
 */
class CatalogueDetails(
    private val core: () -> List<CoreExercise>,
    private val extendedCatalog: ExtendedCatalog,
) {
    private val mutex = Mutex()

    @Volatile
    private var cached: CatalogueIndex? = null

    suspend fun index(): CatalogueIndex = cached ?: mutex.withLock {
        cached ?: build().also { cached = it }
    }

    private suspend fun build(): CatalogueIndex {
        val coreEntries = withContext(Dispatchers.IO) { core() }
        val extended = runCatching { extendedCatalog.all() }.getOrDefault(emptyList())
        return CatalogueIndex.build(coreEntries, extended)
    }
}
