package io.github.kanylbullen.tung.core.importing

import io.github.kanylbullen.tung.core.CoreCatalog
import io.github.kanylbullen.tung.core.ExtendedCatalog
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.command.CandidateResolver
import io.github.kanylbullen.tung.core.command.ExerciseCandidate
import io.github.kanylbullen.tung.core.extendedExerciseId
import java.io.File

/** The repository's catalogue files and the import fixtures, as the core tests read them (working directory `core/`). */
internal object ImportTestFiles {
    fun catalog(name: String): File =
        listOf(File("../catalog/$name"), File("catalog/$name")).firstOrNull { it.isFile }
            ?: error("catalog/$name not found from ${File(".").absolutePath}")

    fun fixture(path: String): String =
        checkNotNull(ImportTestFiles::class.java.getResource("/import/$path")) { "fixture $path missing" }.readText()

    val core by lazy { CoreCatalog.parse(catalog("core.json").readText()) }
    val extended by lazy { catalog("extended.json").inputStream().use(ExtendedCatalog::parse) }
    val jefitAliases by lazy { ImportAliases.parse(catalog("aliases/jefit.json").readText()) }

    /** Every catalogue exercise as the app's resolver offers them: core rows with all their names, then extended entries. */
    val catalogueCandidates: List<ExerciseCandidate> by lazy {
        core.map { ExerciseCandidate(catalogExerciseId(it.key), it.name.values.toList(), nameKey = it.key) } +
            extended.map { ExerciseCandidate(extendedExerciseId(it.id), listOf(it.displayName), extendedId = it.id) }
    }

    fun resolver(extra: List<ExerciseCandidate> = emptyList()) = CandidateResolver(catalogueCandidates + extra)
}
