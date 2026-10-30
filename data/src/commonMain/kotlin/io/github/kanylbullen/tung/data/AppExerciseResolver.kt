package io.github.kanylbullen.tung.data

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.ExtendedCatalog
import io.github.kanylbullen.tung.core.ExtendedExercise
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.command.ExerciseCandidate
import io.github.kanylbullen.tung.core.command.ExerciseResolver
import io.github.kanylbullen.tung.core.extendedExerciseId
import kotlinx.coroutines.withContext

/**
 * Core's [ExerciseResolver] over the app's data: the exercises in Room, the
 * core catalogue's names in every language ([catalogueNames], key → names,
 * read from `core.json` so a Swedish name resolves on an English phone and
 * the other way round), and the extended catalogue for entries not yet in
 * Room. Which match counts is core's decision (`matchExerciseName`); this
 * only hands over the candidates.
 */
class AppExerciseResolver(
    private val dao: ExerciseDao,
    private val catalogueNames: () -> Map<String, List<String>>,
    private val extendedCatalog: ExtendedCatalog,
) : ExerciseResolver {

    // A lookup by key or id needs no names: nothing is matched. The picker's taps go this way.
    override suspend fun lookupKey(nameKey: String): ExerciseCandidate? =
        dao.getById(catalogExerciseId(nameKey))?.takeIf { it.nameKey == nameKey }?.let { candidate(it, emptyMap()) }

    override suspend fun lookupId(id: String): ExerciseCandidate? = dao.getById(id)?.let { candidate(it, emptyMap()) }

    override suspend fun lookupExtended(fedId: String): ExerciseCandidate? =
        extended().firstOrNull { it.id == fedId }?.let(::candidate)

    /** Every exercise in Room, then every extended entry that has no row yet. */
    override suspend fun searchByName(text: String): List<ExerciseCandidate> {
        val names = names()
        val rows = dao.getAll().map { candidate(it, names) }
        val rowIds = rows.mapTo(HashSet()) { it.id }
        return rows + extended().filter { extendedExerciseId(it.id) !in rowIds }.map(::candidate)
    }

    private suspend fun names(): Map<String, List<String>> = withContext(ioDispatcher) { catalogueNames() }

    /** The extended catalogue, or nothing if it cannot be read: resolution then works on Room alone. */
    private suspend fun extended(): List<ExtendedExercise> = runCatching { extendedCatalog.all() }.getOrDefault(emptyList())

    private fun candidate(row: Exercise, names: Map<String, List<String>>) = ExerciseCandidate(
        id = row.id,
        names = (listOf(row.name) + row.nameKey?.let { names[it] }.orEmpty()).distinct(),
        nameKey = row.nameKey,
        equipment = Equipment.fromKey(row.equipment),
        muscleGroup = MuscleGroup.fromKey(row.muscleGroup),
    )

    private fun candidate(entry: ExtendedExercise) = ExerciseCandidate(
        id = extendedExerciseId(entry.id),
        names = listOf(entry.displayName),
        extendedId = entry.id,
        equipment = Equipment.fromKey(entry.equipment),
        muscleGroup = MuscleGroup.fromKey(entry.muscleGroup),
    )
}
