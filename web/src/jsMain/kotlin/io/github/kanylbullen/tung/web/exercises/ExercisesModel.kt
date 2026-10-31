package io.github.kanylbullen.tung.web.exercises

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.ExerciseHistory
import io.github.kanylbullen.tung.core.ExerciseMuscles
import io.github.kanylbullen.tung.core.HistorySet
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.catalog.BrowseItem
import io.github.kanylbullen.tung.core.catalog.ExercisePageRef
import io.github.kanylbullen.tung.core.catalog.LibraryExercise
import io.github.kanylbullen.tung.core.catalog.browseItems
import io.github.kanylbullen.tung.core.exerciseHistory
import io.github.kanylbullen.tung.core.sync.SyncExercise
import io.github.kanylbullen.tung.web.catalog.WebCatalogue
import io.github.kanylbullen.tung.web.home.progressSets
import io.github.kanylbullen.tung.web.store.TungStore

/**
 * The Exercises tab's rows, core's [browseItems]: every exercise in the
 * store by the name on screen, then every extended entry not picked yet.
 */
fun browseItemsOf(store: TungStore, catalogue: WebCatalogue, displayName: (SyncExercise) -> String): List<BrowseItem> =
    browseItems(
        store.exercises.rows.values.map { it.row }.map {
            LibraryExercise(it.id, displayName(it), it.muscleGroup ?: MuscleGroup.FULLBODY.key, it.equipment ?: Equipment.OTHER.key, it.isCustom ?: false)
        },
        catalogue.extended,
    )

/**
 * What an exercise's page shows: the name, group and equipment (the row's
 * when it has one, else the catalogue's), its muscles and instructions from
 * the catalogue, and its history once it has a row ([exerciseId]).
 */
data class ExercisePage(
    val ref: ExercisePageRef,
    /** The store's id: a library exercise's, or an extended entry's once picked; null before. */
    val exerciseId: String?,
    val name: String,
    val group: MuscleGroup,
    val equipment: Equipment,
    val muscles: ExerciseMuscles?,
    val instructions: List<String>,
    val history: ExerciseHistory,
)

/**
 * The page of [ref]; null when it names nothing (a deleted row, an
 * extended id the catalogue does not know or has not loaded yet). An
 * extended entry already picked is shown as its row, whichever ref opened it.
 */
fun exercisePage(
    store: TungStore,
    catalogue: WebCatalogue,
    ref: ExercisePageRef,
    language: String,
    displayName: (SyncExercise) -> String,
): ExercisePage? {
    val rowId = when (ref) {
        is ExercisePageRef.Library -> ref.exerciseId
        is ExercisePageRef.Extended -> ref.exerciseId
    }
    val row = store.exercises.get(rowId)
    val entry = catalogue.index.of(ref) ?: row?.let { catalogue.index.byExerciseId(it.id) }
    if (row == null && entry == null) return null
    val history = if (row == null) ExerciseHistory.EMPTY else exerciseHistory(
        progressSets(store).filter { it.exerciseId == row.id && it.isLogged }.map {
            HistorySet(it.workoutId, it.workoutExerciseId, it.startedAt, it.exercisePosition, it.setPosition, it.set)
        },
    )
    return ExercisePage(
        ref = ref,
        exerciseId = row?.id,
        name = row?.let(displayName) ?: entry?.englishName ?: rowId,
        group = row?.muscleGroup?.let(MuscleGroup::fromKey) ?: entry?.muscleGroup ?: MuscleGroup.FULLBODY,
        equipment = row?.equipment?.let(Equipment::fromKey) ?: entry?.equipment ?: Equipment.OTHER,
        muscles = entry?.muscles,
        instructions = entry?.instructionsIn(language).orEmpty(),
        history = history,
    )
}
