package io.github.kanylbullen.tung.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

/** Names in the extended catalogue; only English exists today. */
@Serializable
data class ExtendedName(val en: String)

/**
 * One entry in `catalog/extended.json`. [muscleGroup] and [equipment] are
 * domain keys; the muscle lists hold [Muscle] keys mapped from the source's.
 */
@Serializable
data class ExtendedExercise(
    val id: String,
    val name: ExtendedName,
    val muscleGroup: String,
    val primaryMuscles: List<String> = emptyList(),
    val secondaryMuscles: List<String> = emptyList(),
    val equipment: String,
    val instructions: List<String> = emptyList(),
    val source: String,
) {
    val displayName: String get() = name.en
}

/**
 * The extended catalogue (`catalog/extended.json`). Where the text comes from
 * is the caller's business: the app reads an asset
 * (`ExtendedCatalog.fromAssets` in the app's `data` package), the server its
 * classpath. It is not in Room: the picker searches it and the Exercises tab
 * lists it, and an entry becomes a Room row only when added somewhere
 * (the executor's `CommandTx.ensureExerciseRow`, named by `ExerciseRef.Extended`). Read once, on
 * [io] (the caller's IO dispatcher where it has one; the default suits a
 * classpath read), the first time [all] is called, then kept in memory.
 */
class ExtendedCatalog(
    private val read: () -> String,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutex = Mutex()

    @Volatile
    private var cached: List<ExtendedExercise>? = null

    suspend fun all(): List<ExtendedExercise> = cached ?: mutex.withLock {
        cached ?: withContext(io) { parse(read()) }.also { cached = it }
    }

    companion object {
        /** Searching the extended catalogue starts at this many characters. */
        const val MIN_QUERY_LENGTH = 2

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): List<ExtendedExercise> = json.decodeFromString(text)

        /**
         * The entries whose English name matches [query], with the same
         * diacritic-insensitive match as the core list. Nothing below
         * [MIN_QUERY_LENGTH] characters, and nothing already in Room
         * ([existingIds]): a picked entry shows up among the ordinary exercises.
         */
        fun search(entries: List<ExtendedExercise>, existingIds: Set<String>, query: String): List<ExtendedExercise> {
            if (normalizeForSearch(query).length < MIN_QUERY_LENGTH) return emptyList()
            val candidates = entries.filter { extendedExerciseId(it.id) !in existingIds }
            return filterByDisplayName(candidates.map { it to it.displayName }, query).map { it.first }
        }
    }
}
