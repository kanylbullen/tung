package io.github.kanylbullen.tung.data

import android.content.Context
import io.github.kanylbullen.tung.domain.filterByDisplayName
import io.github.kanylbullen.tung.domain.normalizeForSearch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.InputStream

/** Names in the extended catalogue; only English exists today. */
@Serializable
data class ExtendedName(val en: String)

/** One entry in `catalog/extended.json`. [muscleGroup] and [equipment] are domain keys. */
@Serializable
data class ExtendedExercise(
    val id: String,
    val name: ExtendedName,
    val muscleGroup: String,
    val equipment: String,
    val instructions: List<String> = emptyList(),
    val source: String,
) {
    val displayName: String get() = name.en
}

/**
 * The extended catalogue (`catalog/extended.json`, shipped as an asset). It is
 * not in Room: the picker searches it, and an entry becomes a Room row only
 * when picked (see [ExerciseRepository.getOrCreateExtended]). Read once, on
 * [io], the first time [all] is called, then kept in memory.
 */
class ExtendedCatalog(
    private val open: () -> InputStream,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    @Volatile
    private var cached: List<ExtendedExercise>? = null

    suspend fun all(): List<ExtendedExercise> = cached ?: mutex.withLock {
        cached ?: withContext(io) { open().use(::parse) }.also { cached = it }
    }

    companion object {
        const val ASSET = "extended.json"

        /** Searching the extended catalogue starts at this many characters. */
        const val MIN_QUERY_LENGTH = 2

        private val json = Json { ignoreUnknownKeys = true }

        fun fromAssets(context: Context): ExtendedCatalog {
            val assets = context.applicationContext.assets
            return ExtendedCatalog({ assets.open(ASSET) })
        }

        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        fun parse(stream: InputStream): List<ExtendedExercise> = json.decodeFromStream(stream)

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
