package io.github.kanylbullen.tung.web.store

import io.github.kanylbullen.tung.core.sync.SyncEntry
import io.github.kanylbullen.tung.core.sync.SyncExercise
import io.github.kanylbullen.tung.core.sync.SyncProgram
import io.github.kanylbullen.tung.core.sync.SyncProgramDay
import io.github.kanylbullen.tung.core.sync.SyncRoutine
import io.github.kanylbullen.tung.core.sync.SyncRoutineExercise
import io.github.kanylbullen.tung.core.sync.SyncSet
import io.github.kanylbullen.tung.core.sync.SyncTable
import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.github.kanylbullen.tung.core.sync.SyncWorkoutExercise
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A row as this browser holds it: the wire row itself ([SyncExercise],
 * [SyncSet], …, the protocol's types), and whether it carries a change the
 * server has not seen. The web keeps no entity classes of its own: what the
 * server stores is what the browser stores.
 */
data class Stored<R : Any>(val row: R, val dirty: Boolean)

/** A deleted row's record for sync: when, and whether the server has heard of it. */
data class Tombstone(val deletedAt: Long, val dirty: Boolean)

/** How a row and its flag are written to IndexedDB: one JSON value per row. */
@Serializable
class StoredRow<R : Any>(val dirty: Boolean, val row: R)

/** How one of the eight tables is read and completed on the web. */
class TableSpec<R : Any>(
    val table: SyncTable,
    val serializer: KSerializer<R>,
    val id: (R) -> String,
    val updatedAt: (R) -> Long,
    /** The rows this row's foreign keys point at; it is stored only when all of them exist. */
    val parents: (R) -> List<Pair<SyncTable, String>>,
    /**
     * The row as stored. A live row must have its required fields, which the
     * wire row may leave null: then the local row's value is taken, and with
     * no local row the row cannot be stored (null). The fields a null from a
     * peer must never erase (`started_by`, Health Connect, the import) keep
     * the local value too, as the phone's `SyncMapping` and the server's
     * `COALESCE` do.
     */
    val complete: (incoming: R, local: R?) -> R?,
)

/** The eight tables' specs, as the phone's `SyncMapping` maps them. */
object TableSpecs {
    val exercises = TableSpec(
        SyncTable.EXERCISES, SyncExercise.serializer(), { it.id }, { it.updatedAt }, { emptyList() },
        ::completeExercise,
    )
    val workouts = TableSpec(
        SyncTable.WORKOUTS, SyncWorkout.serializer(), { it.id }, { it.updatedAt }, { emptyList() },
        ::completeWorkout,
    )
    val routines = TableSpec(
        SyncTable.ROUTINES, SyncRoutine.serializer(), { it.id }, { it.updatedAt }, { emptyList() },
        ::completeRoutine,
    )
    val programs = TableSpec(
        SyncTable.PROGRAMS, SyncProgram.serializer(), { it.id }, { it.updatedAt }, { emptyList() },
        ::completeProgram,
    )
    val workoutExercises = TableSpec(
        SyncTable.WORKOUT_EXERCISES, SyncWorkoutExercise.serializer(), { it.id }, { it.updatedAt },
        { listOfNotNull(it.workoutId?.let { id -> SyncTable.WORKOUTS to id }, it.exerciseId?.let { id -> SyncTable.EXERCISES to id }) },
        ::completeWorkoutExercise,
    )
    val routineExercises = TableSpec(
        SyncTable.ROUTINE_EXERCISES, SyncRoutineExercise.serializer(), { it.id }, { it.updatedAt },
        { listOfNotNull(it.routineId?.let { id -> SyncTable.ROUTINES to id }, it.exerciseId?.let { id -> SyncTable.EXERCISES to id }) },
        ::completeRoutineExercise,
    )
    val programDays = TableSpec(
        SyncTable.PROGRAM_DAYS, SyncProgramDay.serializer(), { it.id }, { it.updatedAt },
        { listOfNotNull(it.programId?.let { id -> SyncTable.PROGRAMS to id }, it.routineId?.let { id -> SyncTable.ROUTINES to id }) },
        ::completeProgramDay,
    )
    val sets = TableSpec(
        SyncTable.SETS, SyncSet.serializer(), { it.id }, { it.updatedAt },
        { listOfNotNull(it.workoutExerciseId?.let { id -> SyncTable.WORKOUT_EXERCISES to id }) },
        ::completeSet,
    )

    private fun completeExercise(r: SyncExercise, l: SyncExercise?): SyncExercise? {
        return SyncExercise(
            id = r.id,
            nameKey = r.nameKey,
            name = r.name ?: l?.name ?: return null,
            muscleGroup = r.muscleGroup ?: l?.muscleGroup ?: return null,
            equipment = r.equipment ?: l?.equipment ?: return null,
            isCustom = r.isCustom ?: l?.isCustom ?: return null,
            restSeconds = r.restSeconds,
            updatedAt = r.updatedAt,
        )
    }

    private fun completeWorkout(r: SyncWorkout, l: SyncWorkout?): SyncWorkout? {
        return SyncWorkout(
            id = r.id,
            startedAt = r.startedAt ?: l?.startedAt ?: return null,
            endedAt = r.endedAt,
            note = r.note,
            routineId = r.routineId,
            programDayId = r.programDayId,
            updatedAt = r.updatedAt,
            startedBy = r.startedBy ?: l?.startedBy,
            avgHeartRate = r.avgHeartRate ?: l?.avgHeartRate,
            maxHeartRate = r.maxHeartRate ?: l?.maxHeartRate,
            activeCalories = r.activeCalories ?: l?.activeCalories,
            healthConnectId = r.healthConnectId ?: l?.healthConnectId,
            importSource = r.importSource ?: l?.importSource,
            importBatchId = r.importBatchId ?: l?.importBatchId,
        )
    }

    private fun completeWorkoutExercise(r: SyncWorkoutExercise, l: SyncWorkoutExercise?): SyncWorkoutExercise? {
        return SyncWorkoutExercise(
            id = r.id,
            workoutId = r.workoutId ?: l?.workoutId ?: return null,
            exerciseId = r.exerciseId ?: l?.exerciseId ?: return null,
            position = r.position ?: l?.position ?: return null,
            updatedAt = r.updatedAt,
        )
    }

    private fun completeSet(r: SyncSet, l: SyncSet?): SyncSet? {
        return SyncSet(
            id = r.id,
            workoutExerciseId = r.workoutExerciseId ?: l?.workoutExerciseId ?: return null,
            position = r.position ?: l?.position ?: return null,
            weightKg = r.weightKg ?: l?.weightKg ?: return null,
            reps = r.reps ?: l?.reps ?: return null,
            completed = r.completed ?: l?.completed ?: return null,
            updatedAt = r.updatedAt,
        )
    }

    private fun completeRoutine(r: SyncRoutine, l: SyncRoutine?): SyncRoutine? {
        return SyncRoutine(
            id = r.id,
            name = r.name ?: l?.name ?: return null,
            lastUsedAt = r.lastUsedAt,
            updatedAt = r.updatedAt,
        )
    }

    private fun completeRoutineExercise(r: SyncRoutineExercise, l: SyncRoutineExercise?): SyncRoutineExercise? {
        return SyncRoutineExercise(
            id = r.id,
            routineId = r.routineId ?: l?.routineId ?: return null,
            exerciseId = r.exerciseId ?: l?.exerciseId ?: return null,
            position = r.position ?: l?.position ?: return null,
            targetSets = r.targetSets ?: l?.targetSets ?: return null,
            targetReps = r.targetReps,
            updatedAt = r.updatedAt,
        )
    }

    private fun completeProgram(r: SyncProgram, l: SyncProgram?): SyncProgram? {
        return SyncProgram(
            id = r.id,
            name = r.name ?: l?.name ?: return null,
            mode = r.mode ?: l?.mode ?: return null,
            isActive = r.isActive ?: l?.isActive ?: return null,
            updatedAt = r.updatedAt,
        )
    }

    private fun completeProgramDay(r: SyncProgramDay, l: SyncProgramDay?): SyncProgramDay? {
        return SyncProgramDay(
            id = r.id,
            programId = r.programId ?: l?.programId ?: return null,
            routineId = r.routineId ?: l?.routineId ?: return null,
            position = r.position ?: l?.position ?: return null,
            weekdays = r.weekdays ?: l?.weekdays ?: return null,
            updatedAt = r.updatedAt,
        )
    }
}

/** The JSON the rows are written to IndexedDB with: the wire shape, every field. */
val StoreJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/** One table in memory: its live rows by id, with their dirty flags. */
class Table<R : Any>(val spec: TableSpec<R>) {
    val rows: MutableMap<String, Stored<R>> = LinkedHashMap()

    val table: SyncTable get() = spec.table

    fun get(id: String): R? = rows[id]?.row

    fun put(row: R, dirty: Boolean) {
        rows[spec.id(row)] = Stored(row, dirty)
    }

    fun remove(id: String): Boolean = rows.remove(id) != null

    fun updatedAt(id: String): Long? = rows[id]?.let { spec.updatedAt(it.row) }

    fun entry(stored: Stored<R>): SyncEntry = SyncEntry(spec.table, spec.id(stored.row), spec.updatedAt(stored.row), null, stored.row)

    fun dirtyEntries(): List<SyncEntry> = rows.values.filter { it.dirty }.map { entry(it) }

    /**
     * Stores [row] (a wire row of this table's type) as the server has it:
     * completed from the local row, not dirty, only when every parent
     * [exists]. Returns the stored row's encoding for the journal, or null
     * when the row could not be stored.
     */
    fun storeIncoming(row: Any, exists: (SyncTable, String) -> Boolean): String? {
        @Suppress("UNCHECKED_CAST")
        val incoming = row as R
        val completed = spec.complete(incoming, get(spec.id(incoming))) ?: return null
        if (!spec.parents(completed).all { (table, id) -> exists(table, id) }) return null
        val stored = Stored(completed, dirty = false)
        rows[spec.id(completed)] = stored
        return encode(stored)
    }

    /** Clears the flag of [id] if it is dirty and still has [updatedAt]; false otherwise. */
    fun clearDirty(id: String, updatedAt: Long): Boolean {
        val stored = rows[id] ?: return false
        if (!stored.dirty || spec.updatedAt(stored.row) != updatedAt) return false
        rows[id] = stored.copy(dirty = false)
        return true
    }

    /** Marks [id] dirty; false when it was already, or is not here. */
    fun markDirty(id: String): Boolean {
        val stored = rows[id] ?: return false
        if (stored.dirty) return false
        rows[id] = stored.copy(dirty = true)
        return true
    }

    /** The row [id] as [encode] writes it, or null when it is not here. */
    fun encoded(id: String): String? = rows[id]?.let { encode(it) }

    fun encode(stored: Stored<R>): String =
        StoreJson.encodeToString(StoredRow.serializer(spec.serializer), StoredRow(stored.dirty, stored.row))

    fun decode(text: String): Stored<R> {
        val stored = StoreJson.decodeFromString(StoredRow.serializer(spec.serializer), text)
        return Stored(stored.row, stored.dirty)
    }

    /** Reads a row written by [encode] into the table. */
    fun load(text: String) {
        val stored = decode(text)
        rows[spec.id(stored.row)] = stored
    }
}
