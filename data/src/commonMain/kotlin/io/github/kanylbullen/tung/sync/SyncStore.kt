package io.github.kanylbullen.tung.sync

import io.github.kanylbullen.tung.core.sync.LocalChanges
import io.github.kanylbullen.tung.core.sync.LocalVersion
import io.github.kanylbullen.tung.core.sync.SyncAction
import io.github.kanylbullen.tung.core.sync.SyncApplier
import io.github.kanylbullen.tung.core.sync.SyncChanges
import io.github.kanylbullen.tung.core.sync.SyncEntry
import io.github.kanylbullen.tung.core.sync.SyncLocalStore
import io.github.kanylbullen.tung.core.sync.SyncResponse
import io.github.kanylbullen.tung.core.sync.SyncTable
import io.github.kanylbullen.tung.core.sync.entries
import io.github.kanylbullen.tung.core.sync.toChanges
import io.github.kanylbullen.tung.core.sync.tombstoneEntry
import io.github.kanylbullen.tung.data.SyncDao
import io.github.kanylbullen.tung.data.TungDatabase
import io.github.kanylbullen.tung.data.transaction


/**
 * Sync's side of Room: reads what is dirty, and merges a server response in
 * one transaction ([apply]). The decisions are core's ([SyncApplier]); this
 * class only reads the local versions and carries them out.
 */
class SyncStore(private val db: TungDatabase) : SyncLocalStore {

    private val dao: SyncDao = db.syncDao()
    private val tombstones = db.tombstoneDao()

    /** Everything dirty, read in one transaction so rows and tombstones agree. */
    override suspend fun readDirty(): LocalChanges = db.transaction {
        val rows = tables.values.flatMap { it.dirtyEntries() }
        val deleted = tombstones.dirty().mapNotNull { t ->
            SyncTable.fromKey(t.tableName)?.let { tombstoneEntry(it, t.id, t.deletedAt) }
        }
        LocalChanges(rows.toChanges(), deleted.toChanges())
    }

    /**
     * Merges [response] into Room and clears the dirty flags of [pushed], the
     * changes of the request it answers, in one transaction.
     *
     * Rows are upserted parents first and deleted children first. A row is
     * skipped, not stored, when it lacks a field Room requires and there is
     * no local row to take it from, or when its parent is not on this device
     * (a foreign key would fail); an exercise still used by a workout or a
     * routine is not deleted. A row in [pushed] is cleared only if it still
     * has the `updatedAt` it was pushed with, so an edit made while the
     * request was in flight stays dirty and goes with the next sync.
     *
     * @return how many rows of the response changed something here.
     */
    override suspend fun apply(response: SyncResponse, pushed: SyncChanges): Int = db.transaction {
        val incoming = response.changes.entries()
        val localRows = HashMap<SyncTable, Map<String, Any>>()
        val localTombstones = HashMap<SyncTable, Map<String, Long>>()
        incoming.groupBy { it.table }.forEach { (table, entries) ->
            val ids = entries.map { it.id }.distinct()
            localRows[table] = ops(table).byIds(ids)
            localTombstones[table] = ids.chunked(IN_LIMIT)
                .flatMap { tombstones.byIds(table.key, it) }
                .associate { it.id to it.deletedAt }
        }
        val decisions = SyncApplier.decide(response) { table, id ->
            localRows[table]?.get(id)?.let { LocalVersion(ops(table).updatedAtOf(it)) }
                ?: localTombstones[table]?.get(id)?.let { LocalVersion(it, deleted = true) }
        }

        val present = HashMap<SyncTable, MutableSet<String>>()
        suspend fun exists(table: SyncTable, id: String): Boolean {
            val known = present.getOrPut(table) { HashSet() }
            if (id in known) return true
            return ops(table).byIds(listOf(id)).isNotEmpty().also { if (it) known += id }
        }

        var changed = 0
        decisions.filter { it.action == SyncAction.Insert || it.action == SyncAction.Update }.forEach { decision ->
            val entry = decision.entry
            val ops = ops(entry.table)
            val entity = ops.toEntity(entry.row, localRows[entry.table]?.get(entry.id)) ?: return@forEach
            if (!ops.parentsOf(entity).all { (table, id) -> exists(table, id) }) return@forEach
            ops.upsert(entity)
            present.getOrPut(entry.table) { HashSet() } += entry.id
            if (localTombstones[entry.table]?.containsKey(entry.id) == true) tombstones.delete(entry.table.key, entry.id)
            changed++
        }
        decisions.filter { it.action == SyncAction.Delete }.asReversed().forEach { decision ->
            val entry = decision.entry
            if (entry.table == SyncTable.EXERCISES && dao.exerciseInUse(entry.id)) return@forEach
            ops(entry.table).delete(entry.id)
            changed++
        }

        pushed.entries().forEach { entry ->
            val deletedAt = entry.deletedAt
            if (deletedAt != null) {
                tombstones.clearDirty(entry.table.key, entry.id, deletedAt)
            } else {
                ops(entry.table).clear(entry.id, entry.updatedAt)
            }
        }
        changed
    }

    /**
     * A new account (or server): everything the user has goes up again, every
     * tombstone too. The server ignores what it already has (equal `updated_at`).
     */
    suspend fun markEverythingDirty() = db.transaction {
        tables.values.forEach { it.markDirty() }
        tombstones.markAllDirty()
    }

    private fun ops(table: SyncTable): TableOps<*, *> = tables.getValue(table)

    /** How one table maps between Room and the wire; [E] the entity, [R] the wire row. */
    private class TableOps<E : Any, R : Any>(
        val table: SyncTable,
        private val dirty: suspend () -> List<E>,
        private val byIdsChunk: suspend (List<String>) -> List<E>,
        private val idOf: (E) -> String,
        private val updatedAt: (E) -> Long,
        private val wire: (E) -> R,
        private val entity: (R, E?) -> E?,
        private val parents: (E) -> List<Pair<SyncTable, String>>,
        private val upsertRow: suspend (E) -> Unit,
        val delete: suspend (String) -> Unit,
        val clear: suspend (String, Long) -> Unit,
        val markDirty: suspend () -> Unit,
    ) {
        suspend fun dirtyEntries(): List<SyncEntry> =
            dirty().map { SyncEntry(table, idOf(it), updatedAt(it), null, wire(it)) }

        suspend fun byIds(ids: List<String>): Map<String, Any> =
            ids.chunked(IN_LIMIT).flatMap { byIdsChunk(it) }.associateBy(idOf)

        @Suppress("UNCHECKED_CAST")
        fun updatedAtOf(row: Any): Long = updatedAt(row as E)

        @Suppress("UNCHECKED_CAST")
        fun toEntity(row: Any, local: Any?): Any? = entity(row as R, local as E?)

        @Suppress("UNCHECKED_CAST")
        fun parentsOf(row: Any): List<Pair<SyncTable, String>> = parents(row as E)

        @Suppress("UNCHECKED_CAST")
        suspend fun upsert(row: Any) = upsertRow(row as E)
    }

    private val tables: Map<SyncTable, TableOps<*, *>> = listOf(
        TableOps(
            SyncTable.EXERCISES, dao::dirtyExercises, dao::exercises, { it.id }, { it.updatedAt },
            { it.toSync() }, { r, l -> r.toEntity(l) }, { emptyList() },
            dao::upsertExercise, dao::deleteExercise, dao::clearExercise, dao::markExercisesDirty,
        ),
        TableOps(
            SyncTable.WORKOUTS, dao::dirtyWorkouts, dao::workouts, { it.id }, { it.updatedAt },
            { it.toSync() }, { r, l -> r.toEntity(l) }, { emptyList() },
            dao::upsertWorkout, dao::deleteWorkout, dao::clearWorkout, dao::markWorkoutsDirty,
        ),
        TableOps(
            SyncTable.ROUTINES, dao::dirtyRoutines, dao::routines, { it.id }, { it.updatedAt },
            { it.toSync() }, { r, l -> r.toEntity(l) }, { emptyList() },
            dao::upsertRoutine, dao::deleteRoutine, dao::clearRoutine, dao::markRoutinesDirty,
        ),
        TableOps(
            SyncTable.PROGRAMS, dao::dirtyPrograms, dao::programs, { it.id }, { it.updatedAt },
            { it.toSync() }, { r, l -> r.toEntity(l) }, { emptyList() },
            dao::upsertProgram, dao::deleteProgram, dao::clearProgram, dao::markProgramsDirty,
        ),
        TableOps(
            SyncTable.WORKOUT_EXERCISES, dao::dirtyWorkoutExercises, dao::workoutExercises, { it.id }, { it.updatedAt },
            { it.toSync() }, { r, l -> r.toEntity(l) },
            { listOf(SyncTable.WORKOUTS to it.workoutId, SyncTable.EXERCISES to it.exerciseId) },
            dao::upsertWorkoutExercise, dao::deleteWorkoutExercise, dao::clearWorkoutExercise, dao::markWorkoutExercisesDirty,
        ),
        TableOps(
            SyncTable.ROUTINE_EXERCISES, dao::dirtyRoutineExercises, dao::routineExercises, { it.id }, { it.updatedAt },
            { it.toSync() }, { r, l -> r.toEntity(l) },
            { listOf(SyncTable.ROUTINES to it.routineId, SyncTable.EXERCISES to it.exerciseId) },
            dao::upsertRoutineExercise, dao::deleteRoutineExercise, dao::clearRoutineExercise, dao::markRoutineExercisesDirty,
        ),
        TableOps(
            SyncTable.PROGRAM_DAYS, dao::dirtyProgramDays, dao::programDays, { it.id }, { it.updatedAt },
            { it.toSync() }, { r, l -> r.toEntity(l) },
            { listOf(SyncTable.PROGRAMS to it.programId, SyncTable.ROUTINES to it.routineId) },
            dao::upsertProgramDay, dao::deleteProgramDay, dao::clearProgramDay, dao::markProgramDaysDirty,
        ),
        TableOps(
            SyncTable.SETS, dao::dirtySets, dao::sets, { it.id }, { it.updatedAt },
            { it.toSync() }, { r, l -> r.toEntity(l) },
            { listOf(SyncTable.WORKOUT_EXERCISES to it.workoutExerciseId) },
            dao::upsertSet, dao::deleteSet, dao::clearSet, dao::markSetsDirty,
        ),
    ).associateBy { it.table }

    private companion object {
        /** Ids per `IN (…)` query, under SQLite's default limit of 999 variables. */
        const val IN_LIMIT = 900
    }
}
