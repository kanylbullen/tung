package io.github.kanylbullen.tung.web.store

import io.github.kanylbullen.tung.core.CoreExercise
import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.sync.LocalChanges
import io.github.kanylbullen.tung.core.sync.LocalVersion
import io.github.kanylbullen.tung.core.sync.SyncAction
import io.github.kanylbullen.tung.core.sync.SyncApplier
import io.github.kanylbullen.tung.core.sync.SyncChanges
import io.github.kanylbullen.tung.core.sync.SyncExercise
import io.github.kanylbullen.tung.core.sync.SyncResponse
import io.github.kanylbullen.tung.core.sync.SyncTable
import io.github.kanylbullen.tung.core.sync.SyncWorkoutExercise
import io.github.kanylbullen.tung.core.sync.entries
import io.github.kanylbullen.tung.core.sync.toChanges
import io.github.kanylbullen.tung.core.sync.tombstoneEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One change to carry to IndexedDB. The store changes memory first and
 * records what it did; the caller writes the journal in one IndexedDB
 * transaction ([IndexedDbStore.write]). Memory is the truth while the page
 * lives, IndexedDB is what the next page load starts from.
 */
sealed interface Write {
    /** The row [id] of [table], as [Table.encode] writes it. */
    data class Put(val table: SyncTable, val id: String, val value: String) : Write
    data class Delete(val table: SyncTable, val id: String) : Write
    data class PutTombstone(val table: SyncTable, val id: String, val value: String) : Write
    data class DeleteTombstone(val table: SyncTable, val id: String) : Write
}

/**
 * The training log in memory: the eight tables of wire rows with their dirty
 * flags, and the tombstones. A personal log is small (thousands of sets),
 * so the whole of it lives here and every screen reads it directly;
 * [version] ticks after each batch of changes, which is how a screen knows
 * to recompute. IndexedDB holds a copy for the next page load
 * ([IndexedDbStore]), written from the journal each change appends to.
 *
 * Why not read IndexedDB row by row: an IndexedDB transaction closes as soon
 * as control returns to the event loop with no request pending, and a
 * coroutine resumed through a dispatcher does exactly that. Reading
 * everything once and writing a journal per change keeps every transaction
 * synchronous inside itself.
 */
class TungStore {
    val exercises = Table(TableSpecs.exercises)
    val workouts = Table(TableSpecs.workouts)
    val routines = Table(TableSpecs.routines)
    val programs = Table(TableSpecs.programs)
    val workoutExercises = Table(TableSpecs.workoutExercises)
    val routineExercises = Table(TableSpecs.routineExercises)
    val programDays = Table(TableSpecs.programDays)
    val sets = Table(TableSpecs.sets)

    /** The tables parents first ([SyncTable] order). */
    val tables: Map<SyncTable, Table<*>> = listOf(
        exercises, workouts, routines, programs, workoutExercises, routineExercises, programDays, sets,
    ).associateBy { it.table }

    val tombstones: MutableMap<Pair<SyncTable, String>, Tombstone> = LinkedHashMap()

    private val _version = MutableStateFlow(0L)

    /** Ticks after every batch of changes; a screen recomputes what it shows on each tick. */
    val version: StateFlow<Long> = _version.asStateFlow()

    fun table(table: SyncTable): Table<*> = tables.getValue(table)

    /** Tells the screens the store changed. */
    fun bump() {
        _version.value = _version.value + 1
    }

    fun exists(table: SyncTable, id: String): Boolean = table(table).rows.containsKey(id)

    /** Everything dirty, rows and tombstones, as the sync planner takes it. */
    fun localChanges(): LocalChanges {
        val rows = tables.values.flatMap { it.dirtyEntries() }
        val deleted = tombstones.filter { it.value.dirty }.map { (key, t) -> tombstoneEntry(key.first, key.second, t.deletedAt) }
        return LocalChanges(rows.toChanges(), deleted.toChanges())
    }

    /**
     * Merges [response] and clears the dirty flags of [pushed], the changes
     * of the request it answers: the phone's `SyncStore.apply`, over memory.
     *
     * Rows are stored parents first and deleted children first, by core's
     * [SyncApplier] (strictly newer wins). A row is skipped when it lacks a
     * field the device requires and there is no local row to take it from,
     * or when its parent is not here; an exercise still used by a workout or
     * a routine is not deleted; a deleted parent takes its children with it,
     * as Room's cascades do. A row in [pushed] is cleared only if it still
     * has the `updatedAt` it was pushed with, so an edit made while the
     * request was in flight stays dirty and goes with the next sync.
     *
     * @return how many rows of the response changed something here.
     */
    fun apply(response: SyncResponse, pushed: SyncChanges, journal: MutableList<Write>): Int {
        val decisions = SyncApplier.decide(response) { table, id ->
            table(table).updatedAt(id)?.let { LocalVersion(it) }
                ?: tombstones[table to id]?.let { LocalVersion(it.deletedAt, deleted = true) }
        }
        var changed = 0
        decisions.filter { it.action == SyncAction.Insert || it.action == SyncAction.Update }.forEach { decision ->
            val entry = decision.entry
            val table = table(entry.table)
            val value = table.storeIncoming(entry.row, ::exists) ?: return@forEach
            journal += Write.Put(entry.table, entry.id, value)
            if (tombstones.remove(entry.table to entry.id) != null) journal += Write.DeleteTombstone(entry.table, entry.id)
            changed++
        }
        decisions.filter { it.action == SyncAction.Delete }.asReversed().forEach { decision ->
            val entry = decision.entry
            if (entry.table == SyncTable.EXERCISES && exerciseInUse(entry.id)) return@forEach
            if (deleteCascading(entry.table, entry.id, journal)) changed++
        }
        pushed.entries().forEach { entry ->
            val deletedAt = entry.deletedAt
            if (deletedAt != null) {
                val key = entry.table to entry.id
                val tombstone = tombstones[key] ?: return@forEach
                if (tombstone.dirty && tombstone.deletedAt == deletedAt) {
                    tombstones[key] = tombstone.copy(dirty = false)
                    journal += Write.PutTombstone(entry.table, entry.id, encodeTombstone(entry.table, entry.id, tombstones.getValue(key)))
                }
            } else {
                val table = table(entry.table)
                if (table.clearDirty(entry.id, entry.updatedAt)) {
                    journal += Write.Put(entry.table, entry.id, table.encoded(entry.id) ?: return@forEach)
                }
            }
        }
        return changed
    }

    /** A catalogue exercise as seeded (`updatedAt` 0): the phone's `markExercisesDirty` leaves it out too. */
    private fun isUntouchedCatalogueRow(id: String): Boolean =
        exercises.get(id)?.let { it.nameKey != null && it.updatedAt == 0L } == true

    /**
     * The phone's `previousSessionSets`: the ticked sets of the first time
     * [exerciseId] appears in the latest finished workout other than
     * [excludeWorkoutId], in order. Empty with no history. The pre-fill and
     * "Last: 80 × 8" both read it.
     */
    fun previousSession(exerciseId: String, excludeWorkoutId: String?): List<LoggedSet> {
        val finished = workouts.rows.values.map { it.row }
            .filter { it.endedAt != null && it.id != excludeWorkoutId }
            .associateBy { it.id }
        val latest = workoutExercises.rows.values.map { it.row }
            .filter { it.exerciseId == exerciseId && it.workoutId in finished }
            .sortedWith(compareByDescending<SyncWorkoutExercise> { finished[it.workoutId]?.startedAt ?: 0L }.thenBy { it.position ?: 0 })
            .firstOrNull() ?: return emptyList()
        return sets.rows.values.map { it.row }
            .filter { it.workoutExerciseId == latest.id && it.completed == true }
            .sortedBy { it.position ?: 0 }
            .map { LoggedSet(it.weightKg ?: 0.0, it.reps ?: 0) }
    }

    /** True when a workout or a routine still refers to the exercise. */
    fun exerciseInUse(id: String): Boolean =
        workoutExercises.rows.values.any { it.row.exerciseId == id } ||
            routineExercises.rows.values.any { it.row.exerciseId == id }

    /**
     * The catalogue's exercises as rows, for the ones not here yet: the
     * phone seeds the same seventy on first open, with the same ids
     * ([catalogExerciseId]) and `updatedAt` 0, not dirty, so they never go
     * up. The English name is the stored name, as on the phone; the screen
     * shows the catalogue's name in its own language.
     *
     * @return how many were added.
     */
    fun seedCatalogue(catalogue: List<CoreExercise>, journal: MutableList<Write>): Int {
        var added = 0
        catalogue.forEach { entry ->
            val id = catalogExerciseId(entry.key)
            if (exercises.rows.containsKey(id)) return@forEach
            val stored = Stored(
                SyncExercise(
                    id = id,
                    nameKey = entry.key,
                    name = entry.name["en"] ?: entry.key,
                    muscleGroup = entry.muscleGroup,
                    equipment = entry.equipment,
                    isCustom = false,
                    restSeconds = null,
                    updatedAt = 0,
                ),
                dirty = false,
            )
            exercises.rows[id] = stored
            journal += Write.Put(SyncTable.EXERCISES, id, exercises.encode(stored))
            added++
        }
        return added
    }

    /**
     * A new account (or server): everything the user has goes up again,
     * every tombstone too; the catalogue's untouched rows (`updatedAt` 0)
     * stay as they are, as on the phone. The server ignores what it already
     * has (equal `updated_at`).
     */
    fun markEverythingDirty(journal: MutableList<Write>) {
        tables.values.forEach { table ->
            table.rows.keys.toList().forEach { id ->
                if (table === exercises && isUntouchedCatalogueRow(id)) return@forEach
                if (table.markDirty(id)) journal += Write.Put(table.table, id, table.encoded(id) ?: return@forEach)
            }
        }
        tombstones.keys.toList().forEach { key ->
            val tombstone = tombstones.getValue(key)
            if (tombstone.dirty) return@forEach
            tombstones[key] = tombstone.copy(dirty = true)
            journal += Write.PutTombstone(key.first, key.second, encodeTombstone(key.first, key.second, tombstones.getValue(key)))
        }
    }

    /** Reads a tombstone written by [encodeTombstone]. */
    fun loadTombstone(text: String) {
        val stored = StoreJson.decodeFromString(StoredTombstone.serializer(), text)
        val table = SyncTable.fromKey(stored.table) ?: return
        tombstones[table to stored.id] = Tombstone(stored.deletedAt, stored.dirty)
    }

    fun encodeTombstone(table: SyncTable, id: String, tombstone: Tombstone): String =
        StoreJson.encodeToString(StoredTombstone.serializer(), StoredTombstone(table.key, id, tombstone.deletedAt, tombstone.dirty))

    /** The rows whose foreign keys point at [id] of [table], as Room's cascades would delete them. */
    private fun children(table: SyncTable, id: String): List<Pair<SyncTable, String>> = when (table) {
        SyncTable.WORKOUTS -> workoutExercises.rows.values.filter { it.row.workoutId == id }.map { SyncTable.WORKOUT_EXERCISES to it.row.id }
        SyncTable.WORKOUT_EXERCISES -> sets.rows.values.filter { it.row.workoutExerciseId == id }.map { SyncTable.SETS to it.row.id }
        SyncTable.ROUTINES ->
            routineExercises.rows.values.filter { it.row.routineId == id }.map { SyncTable.ROUTINE_EXERCISES to it.row.id } +
                programDays.rows.values.filter { it.row.routineId == id }.map { SyncTable.PROGRAM_DAYS to it.row.id }
        SyncTable.PROGRAMS -> programDays.rows.values.filter { it.row.programId == id }.map { SyncTable.PROGRAM_DAYS to it.row.id }
        SyncTable.EXERCISES, SyncTable.ROUTINE_EXERCISES, SyncTable.PROGRAM_DAYS, SyncTable.SETS -> emptyList()
    }

    /** Deletes the row and, first, every row that depends on it; false when there was no such row. A sync's delete writes no tombstone: the server has it. */
    private fun deleteCascading(table: SyncTable, id: String, journal: MutableList<Write>): Boolean {
        if (!exists(table, id)) return false
        children(table, id).forEach { (childTable, childId) -> deleteCascading(childTable, childId, journal) }
        table(table).remove(id)
        journal += Write.Delete(table, id)
        return true
    }
}

/** A tombstone as written to IndexedDB, with its table and id so a load needs no key. */
@kotlinx.serialization.Serializable
class StoredTombstone(val table: String, val id: String, val deletedAt: Long, val dirty: Boolean)
