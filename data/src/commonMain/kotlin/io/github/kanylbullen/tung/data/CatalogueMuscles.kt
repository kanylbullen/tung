package io.github.kanylbullen.tung.data

import io.github.kanylbullen.tung.core.CoreExercise
import io.github.kanylbullen.tung.core.ExtendedCatalog
import io.github.kanylbullen.tung.core.MuscleIndex
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/**
 * Core's [MuscleIndex] over the catalogue files the APK ships: `core.json`
 * ([core], read by the container) and the extended catalogue. Built once, on
 * IO, the first time [index] is called. If the extended catalogue cannot be
 * read, the index has the core exercises only; the muscles are a hint for the
 * picker, never a reason to fail.
 */
class CatalogueMuscles(
    private val core: () -> List<CoreExercise>,
    private val extendedCatalog: ExtendedCatalog,
) {
    private val mutex = Mutex()

    @Volatile
    private var cached: MuscleIndex? = null

    suspend fun index(): MuscleIndex = cached ?: mutex.withLock {
        cached ?: build().also { cached = it }
    }

    private suspend fun build(): MuscleIndex {
        val coreEntries = withContext(ioDispatcher) { core() }
        val extended = runCatching { extendedCatalog.all() }.getOrDefault(emptyList())
        return MuscleIndex.build(coreEntries, extended)
    }
}
