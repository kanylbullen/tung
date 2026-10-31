package io.github.kanylbullen.tung.web.catalog

import io.github.kanylbullen.tung.core.CoreExercise
import io.github.kanylbullen.tung.core.ExtendedExercise
import io.github.kanylbullen.tung.core.MuscleIndex
import io.github.kanylbullen.tung.core.catalog.CatalogueIndex

/**
 * Both catalogue files as the screens read them, the phone's
 * `container.catalogueMuscles` and `container.catalogueDetails`: the
 * muscles of every catalogue exercise and what an exercise's page shows.
 * The core file comes first and the extended one after it, so [extended]
 * is empty for a moment after start (and stays empty offline before its
 * first load).
 */
class WebCatalogue(val core: List<CoreExercise>, val extended: List<ExtendedExercise>) {
    val muscles: MuscleIndex by lazy { MuscleIndex.build(core, extended) }
    val index: CatalogueIndex by lazy { CatalogueIndex.build(core, extended) }

    companion object {
        val EMPTY = WebCatalogue(emptyList(), emptyList())
    }
}
