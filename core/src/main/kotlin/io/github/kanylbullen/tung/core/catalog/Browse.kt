package io.github.kanylbullen.tung.core.catalog

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.ExtendedExercise
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.extendedExerciseId
import io.github.kanylbullen.tung.core.filterByDisplayName
import java.text.Collator
import java.util.Locale

/**
 * What an exercise page shows: a row in the user's library (Room: the core
 * seed, custom exercises and extended entries already picked) or an
 * extended-catalogue entry that has no row yet. [route] is the string form
 * used in the navigation route `exercise/{ref}`: the Room id, or
 * `ext:<free-exercise-db id>`. A Room id is a UUID, so it never starts with
 * the prefix.
 */
sealed interface ExercisePageRef {
    val route: String

    /** The reference a command takes; the handler creates an extended entry's row the first time. */
    fun toExerciseRef(): ExerciseRef

    data class Library(val exerciseId: String) : ExercisePageRef {
        override val route: String get() = exerciseId
        override fun toExerciseRef(): ExerciseRef = ExerciseRef.ById(exerciseId)
    }

    data class Extended(val fedId: String) : ExercisePageRef {
        override val route: String get() = EXTENDED_PREFIX + fedId

        /** The Room id this entry gets once picked ([extendedExerciseId]). */
        val exerciseId: String get() = extendedExerciseId(fedId)

        override fun toExerciseRef(): ExerciseRef = ExerciseRef.Extended(fedId)
    }

    companion object {
        const val EXTENDED_PREFIX = "ext:"

        /** The ref [route] names; null for a blank one. */
        fun parse(route: String): ExercisePageRef? = when {
            route.isBlank() -> null
            route.startsWith(EXTENDED_PREFIX) -> route.removePrefix(EXTENDED_PREFIX).takeIf { it.isNotBlank() }?.let(::Extended)
            else -> Library(route)
        }
    }
}

/** An exercise in the user's library as the tab needs it: the Room id, the displayed name, and the group and equipment keys. */
data class LibraryExercise(
    val id: String,
    val displayName: String,
    val muscleGroup: String,
    val equipment: String,
    val isCustom: Boolean = false,
)

/** One row of the Exercises tab. Extended entries carry their English name until picked, as in the picker. */
data class BrowseItem(
    val ref: ExercisePageRef,
    val name: String,
    val group: MuscleGroup,
    val equipment: Equipment,
    val isCustom: Boolean = false,
) {
    val inLibrary: Boolean get() = ref is ExercisePageRef.Library
}

data class BrowseSection(val group: MuscleGroup, val items: List<BrowseItem>)

/**
 * The whole catalogue as the tab lists it: every library exercise, then every
 * extended entry that has no Room row yet. An entry already picked is its
 * library row (with the id [extendedExerciseId]), so it is listed once.
 */
fun browseItems(library: List<LibraryExercise>, extended: List<ExtendedExercise>): List<BrowseItem> {
    val ids = library.mapTo(HashSet()) { it.id }
    val own = library.map {
        BrowseItem(
            ref = ExercisePageRef.Library(it.id),
            name = it.displayName,
            group = MuscleGroup.fromKey(it.muscleGroup),
            equipment = Equipment.fromKey(it.equipment),
            isCustom = it.isCustom,
        )
    }
    val more = extended.filter { extendedExerciseId(it.id) !in ids }.map {
        BrowseItem(
            ref = ExercisePageRef.Extended(it.id),
            name = it.displayName,
            group = MuscleGroup.fromKey(it.muscleGroup),
            equipment = Equipment.fromKey(it.equipment),
        )
    }
    return own + more
}

/**
 * The groups that have at least one item done with [available] equipment, in
 * [MuscleGroup] order: the chips after "All". A group the user's equipment
 * empties gets no chip.
 */
fun browseGroups(items: List<BrowseItem>, available: Set<Equipment> = EquipmentFilter.ALL): List<MuscleGroup> {
    val present = items.filter { passes(it.equipment, available) }.mapTo(HashSet()) { it.group }
    return MuscleGroup.entries.filter { it in present }
}

/** Names in the order of [locale], ignoring case but not accents, as the picker sorts. */
fun browseNameOrder(locale: Locale): Comparator<String> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    return Comparator { a, b -> collator.compare(a, b) }
}

/**
 * The tab's list: [items] in [group] (all groups when null), done with
 * [available] equipment ("My equipment", [passes]), whose displayed name
 * matches [query] (core's [filterByDisplayName], so diacritics and case do not
 * matter), in sections by [MuscleGroup] order. Within a section the
 * rows are sorted by name in [nameOrder], then by ref, so two exercises with
 * the same name keep a stable order and the list keys do not jump.
 */
fun browse(
    items: List<BrowseItem>,
    group: MuscleGroup?,
    query: String,
    nameOrder: Comparator<String>,
    available: Set<Equipment> = EquipmentFilter.ALL,
): List<BrowseSection> {
    val inGroup = items.filter { (group == null || it.group == group) && passes(it.equipment, available) }
    val matching = filterByDisplayName(inGroup.map { it to it.name }, query).map { it.first }
    val order = compareBy(nameOrder) { item: BrowseItem -> item.name }.thenBy { it.ref.route }
    val byGroup = matching.groupBy { it.group }
    return MuscleGroup.entries.mapNotNull { g ->
        byGroup[g]?.let { BrowseSection(g, it.sortedWith(order)) }
    }
}
