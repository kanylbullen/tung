package io.github.kanylbullen.tung.core.catalog

import io.github.kanylbullen.tung.core.Equipment

/**
 * "My equipment": what the user has to train with, a set of [Equipment]. The
 * Exercises tab, the picker's list, "More exercises" and the smart picker's
 * Suggested show only exercises whose equipment is in it. Pure, so the watch
 * or a server could narrow the same way; where the set is kept (the phone's
 * `settings` DataStore) is the caller's business.
 */
object EquipmentFilter {
    /** Everything: the default, and what an unset preference means. */
    val ALL: Set<Equipment> = Equipment.entries.toSet()

    /** The "Bodyweight only" shortcut. */
    val BODYWEIGHT_ONLY: Set<Equipment> = setOf(Equipment.BODYWEIGHT)

    /** Whether [available] is narrower than [ALL], i.e. whether filtering does anything. */
    fun isNarrowed(available: Set<Equipment>): Boolean = !available.containsAll(ALL)

    /** The stored keys as equipment: keys this build does not know are dropped (a newer client may have written them). */
    fun fromKeys(keys: Collection<String>): Set<Equipment> =
        keys.mapNotNullTo(LinkedHashSet()) { key -> Equipment.entries.firstOrNull { it.key == key } }

    /** [available] in [Equipment] order, as keys: what is stored and what the assistant is told. */
    fun toKeys(available: Set<Equipment>): List<String> = Equipment.entries.filter { it in available }.map { it.key }
}

/**
 * Whether an exercise done with [equipment] shows when the user has
 * [available]. Bodyweight exercises pass like any other value, so unticking
 * bodyweight hides them. "An exercise already in the workout or routine
 * always shows" is not this function's concern: the callers keep those with
 * [filterByEquipment]'s allow-list.
 */
fun passes(equipment: Equipment, available: Set<Equipment>): Boolean = equipment in available

/** [passes] for a stored key; an unknown key is [Equipment.OTHER], as [Equipment.fromKey] reads it. */
fun passes(equipmentKey: String?, available: Set<Equipment>): Boolean = passes(Equipment.fromKey(equipmentKey), available)

/**
 * [items] whose equipment [passes] [available], in their order, plus every item
 * whose id is in [alwaysShow] (the exercises already in the workout or routine
 * the picker adds to). With nothing narrowed the list is returned as it is.
 */
fun <T> filterByEquipment(
    items: List<T>,
    available: Set<Equipment>,
    alwaysShow: Set<String> = emptySet(),
    id: (T) -> String,
    equipment: (T) -> Equipment,
): List<T> {
    if (!EquipmentFilter.isNarrowed(available)) return items
    return items.filter { passes(equipment(it), available) || id(it) in alwaysShow }
}
