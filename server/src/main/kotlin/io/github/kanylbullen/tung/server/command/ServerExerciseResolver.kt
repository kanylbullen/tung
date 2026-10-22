package io.github.kanylbullen.tung.server.command

import io.github.kanylbullen.tung.core.CoreCatalog
import io.github.kanylbullen.tung.core.CoreExercise
import io.github.kanylbullen.tung.core.ExtendedCatalog
import io.github.kanylbullen.tung.core.ExtendedExercise
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.command.ExerciseCandidate
import io.github.kanylbullen.tung.core.command.ExerciseResolver
import io.github.kanylbullen.tung.core.extendedExerciseId
import io.github.kanylbullen.tung.server.Exercises
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll

/**
 * Both catalogues as the server holds them, read once at start from the
 * classpath (`/catalog/core.json` and `/catalog/extended.json`, the files the
 * app ships as assets): the core entries with their names in every language,
 * and the extended entries, which only become rows when a command uses one.
 */
class ServerCatalogue(val core: List<CoreExercise>, val extended: List<ExtendedExercise>) {

    /** Core key → every name it answers to, English first. */
    val namesByKey: Map<String, List<String>> = core.associate { entry ->
        entry.key to (listOfNotNull(entry.name["en"]) + entry.name.values).distinct()
    }

    val coreByKey: Map<String, CoreExercise> = core.associateBy { it.key }

    val extendedById: Map<String, ExtendedExercise> = extended.associateBy { it.id }

    companion object {
        fun fromClasspath(): ServerCatalogue {
            fun read(path: String) = ServerCatalogue::class.java.getResourceAsStream(path)
                ?: error("$path is missing from the classpath")
            val core = read("/catalog/core.json").use { CoreCatalog.parse(it.readBytes().toString(Charsets.UTF_8)) }
            val extended = read("/catalog/extended.json").use { ExtendedCatalog.parse(it.readBytes().toString(Charsets.UTF_8)) }
            return ServerCatalogue(core, extended)
        }
    }
}

/**
 * Core's [ExerciseResolver] over one user's exercises on the server: the
 * catalogue rows (`user_id NULL`) shadowed by the user's own live rows, with
 * the core catalogue's names in every language, plus the extended entries
 * that have no row yet. Which match counts is core's decision
 * (`matchExerciseName`), exactly as in the app's `AppExerciseResolver`.
 *
 * Runs its queries in the caller's transaction ([io.github.kanylbullen.tung.server.Db.suspendingTx]).
 */
class ServerExerciseResolver(private val userId: String, private val catalogue: ServerCatalogue) : ExerciseResolver {

    override suspend fun lookupKey(nameKey: String): ExerciseCandidate? =
        visibleRow(catalogExerciseId(nameKey))?.takeIf { it[Exercises.nameKey] == nameKey }?.let(::candidate)

    override suspend fun lookupId(id: String): ExerciseCandidate? = visibleRow(id)?.let(::candidate)

    override suspend fun lookupExtended(fedId: String): ExerciseCandidate? = catalogue.extendedById[fedId]?.let(::candidate)

    /** Every exercise the user sees, then every extended entry that has no row yet. */
    override suspend fun searchByName(text: String): List<ExerciseCandidate> {
        val rows = visibleRows().map(::candidate)
        val rowIds = rows.mapTo(HashSet()) { it.id }
        return rows + catalogue.extended.filter { extendedExerciseId(it.id) !in rowIds }.map(::candidate)
    }

    /** The catalogue plus the user's own live rows; a user's copy of a catalogue exercise replaces it. */
    fun visibleRows(): List<ResultRow> {
        val rows = Exercises.selectAll()
            .where { Exercises.userId.isNull() or ((Exercises.userId eq userId) and Exercises.deletedAt.isNull()) }
            .toList()
        val own = rows.filter { it[Exercises.userId] != null }.associateBy { it[Exercises.id] }
        return rows.filter { it[Exercises.userId] == null && it[Exercises.id] !in own } + own.values
    }

    fun visibleRow(id: String): ResultRow? {
        val rows = Exercises.selectAll()
            .where {
                (Exercises.id eq id) and
                    (Exercises.userId.isNull() or ((Exercises.userId eq userId) and Exercises.deletedAt.isNull()))
            }
            .toList()
        return rows.firstOrNull { it[Exercises.userId] != null } ?: rows.firstOrNull()
    }

    /** Every name a row answers to: its stored (English) name, then the catalogue's names in every language. */
    fun names(row: ResultRow): List<String> =
        (listOf(row[Exercises.name]) + row[Exercises.nameKey]?.let { catalogue.namesByKey[it] }.orEmpty()).distinct()

    private fun candidate(row: ResultRow) = ExerciseCandidate(
        id = row[Exercises.id],
        names = names(row),
        nameKey = row[Exercises.nameKey],
    )

    private fun candidate(entry: ExtendedExercise) = ExerciseCandidate(
        id = extendedExerciseId(entry.id),
        names = listOf(entry.displayName),
        extendedId = entry.id,
    )
}
