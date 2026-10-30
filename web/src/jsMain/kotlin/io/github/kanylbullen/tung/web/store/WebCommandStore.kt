package io.github.kanylbullen.tung.web.store

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.CommandStore
import io.github.kanylbullen.tung.core.command.CommandTx
import io.github.kanylbullen.tung.core.command.ExerciseCandidate
import io.github.kanylbullen.tung.core.command.ExerciseResolver
import io.github.kanylbullen.tung.core.command.ExerciseRow
import io.github.kanylbullen.tung.core.command.ProgramDayRow
import io.github.kanylbullen.tung.core.command.ProgramRow
import io.github.kanylbullen.tung.core.command.RoutineExerciseRow
import io.github.kanylbullen.tung.core.command.RoutineRow
import io.github.kanylbullen.tung.core.command.SetRow
import io.github.kanylbullen.tung.core.command.WorkoutExerciseRow
import io.github.kanylbullen.tung.core.command.WorkoutRow
import io.github.kanylbullen.tung.core.sync.SyncExercise
import io.github.kanylbullen.tung.core.sync.SyncProgram
import io.github.kanylbullen.tung.core.sync.SyncProgramDay
import io.github.kanylbullen.tung.core.sync.SyncRoutine
import io.github.kanylbullen.tung.core.sync.SyncRoutineExercise
import io.github.kanylbullen.tung.core.sync.SyncSet
import io.github.kanylbullen.tung.core.sync.SyncTable
import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.github.kanylbullen.tung.core.sync.SyncWorkoutExercise
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Core's [CommandStore] in the browser: one command at a time over the
 * store in memory, then one IndexedDB transaction with what it wrote
 * ([persist], [IndexedDbStore.write]). A command that ends in anything but
 * `Done`, or throws, is undone in memory and writes nothing, as the server's
 * store rolls back.
 */
class WebCommandStore(
    private val store: TungStore,
    private val persist: suspend (List<Write>) -> Unit,
    private val clock: () -> Long,
) : CommandStore {
    private val mutex = Mutex()

    override suspend fun transaction(block: suspend (CommandTx) -> CommandResult): CommandResult = mutex.withLock {
        val tx = WebCommandTx(store, clock)
        val result = try {
            block(tx)
        } catch (e: Throwable) {
            tx.undo()
            throw e
        }
        if (result !is CommandResult.Done) {
            tx.undo()
            return@withLock result
        }
        try {
            persist(tx.journal)
        } catch (e: Throwable) {
            // Memory never runs ahead of IndexedDB: what could not be stored did not happen.
            tx.undo()
            throw e
        }
        store.bump()
        result
    }
}

/**
 * Core's [CommandTx] over [TungStore]: the wire rows themselves, stamped
 * with [clock] and marked dirty on every write as the phone's `RoomCommandTx`
 * does, a dirty tombstone for every deleted row, and every change recorded
 * twice: in [journal] for IndexedDB, and as its inverse for [undo].
 */
internal class WebCommandTx(private val store: TungStore, private val clock: () -> Long) : CommandTx {
    val journal = ArrayList<Write>()
    private val inverses = ArrayList<() -> Unit>()

    /** Puts memory back as it was before the first change, newest change first. */
    fun undo() {
        inverses.asReversed().forEach { it() }
        inverses.clear()
        journal.clear()
    }

    override fun now(): Long = clock()

    // --- bookkeeping ---

    private fun <R : Any> put(table: Table<R>, row: R) {
        val id = table.spec.id(row)
        val before = table.rows[id]
        val tombstone = store.tombstones[table.table to id]
        table.put(row, dirty = true)
        if (tombstone != null) {
            store.tombstones.remove(table.table to id)
            journal += Write.DeleteTombstone(table.table, id)
        }
        journal += Write.Put(table.table, id, table.encode(Stored(row, dirty = true)))
        inverses += {
            if (before == null) table.rows.remove(id) else table.rows[id] = before
            if (tombstone != null) store.tombstones[table.table to id] = tombstone
        }
    }

    private fun <R : Any> update(table: Table<R>, id: String, change: (R) -> R) {
        val current = table.get(id) ?: return
        put(table, change(current))
    }

    private fun <R : Any> delete(table: Table<R>, id: String) {
        val before = table.rows[id] ?: return
        val tombstoneBefore = store.tombstones[table.table to id]
        val tombstone = Tombstone(deletedAt = clock(), dirty = true)
        table.rows.remove(id)
        store.tombstones[table.table to id] = tombstone
        journal += Write.Delete(table.table, id)
        journal += Write.PutTombstone(table.table, id, store.encodeTombstone(table.table, id, tombstone))
        inverses += {
            table.rows[id] = before
            if (tombstoneBefore == null) store.tombstones.remove(table.table to id) else store.tombstones[table.table to id] = tombstoneBefore
        }
    }

    // --- exercises ---

    override suspend fun exercise(id: String): ExerciseRow? = store.exercises.get(id)?.toRow()

    override suspend fun insertExercise(row: ExerciseRow) = put(store.exercises, row.toWire(clock()))

    override suspend fun ensureExerciseRow(row: ExerciseRow) {
        if (store.exercises.get(row.id) == null) put(store.exercises, row.toWire(clock()))
    }

    override suspend fun setRest(id: String, seconds: Int?) =
        update(store.exercises, id) { it.copy(restSeconds = seconds, updatedAt = clock()) }

    // --- workouts ---

    override suspend fun workout(id: String): WorkoutRow? = store.workouts.get(id)?.toRow()

    override suspend fun activeWorkout(): WorkoutRow? =
        store.workouts.rows.values.mapNotNull { it.row.toRow() }.filter { it.endedAt == null }
            .maxByOrNull { it.startedAt }

    override suspend fun workoutExercise(id: String): WorkoutExerciseRow? = store.workoutExercises.get(id)?.toRow()

    override suspend fun workoutExercises(workoutId: String): List<WorkoutExerciseRow> =
        store.workoutExercises.rows.values.map { it.row }.filter { it.workoutId == workoutId }
            .mapNotNull { it.toRow() }.sortedBy { it.position }

    override suspend fun set(id: String): SetRow? = store.sets.get(id)?.toRow()

    override suspend fun sets(workoutExerciseId: String): List<SetRow> =
        store.sets.rows.values.map { it.row }.filter { it.workoutExerciseId == workoutExerciseId }
            .mapNotNull { it.toRow() }.sortedBy { it.position }

    /** The phone's `previousSessionSets`: the ticked sets of the first time [exerciseId] appears in the latest other finished workout. */
    override suspend fun previousSession(exerciseId: String, excludeWorkoutId: String): List<LoggedSet> {
        val finished = store.workouts.rows.values.map { it.row }
            .filter { it.endedAt != null && it.id != excludeWorkoutId }
            .associateBy { it.id }
        val latest = store.workoutExercises.rows.values.map { it.row }
            .filter { it.exerciseId == exerciseId && it.workoutId in finished }
            .sortedWith(compareByDescending<SyncWorkoutExercise> { finished[it.workoutId]?.startedAt ?: 0 }.thenBy { it.position ?: 0 })
            .firstOrNull() ?: return emptyList()
        return sets(latest.id).filter { it.completed }.map { it.values }
    }

    override suspend fun routineTargetSets(workoutId: String, exerciseId: String): Int? {
        val routineId = store.workouts.get(workoutId)?.routineId ?: return null
        return store.routineExercises.rows.values.map { it.row }
            .filter { it.routineId == routineId && it.exerciseId == exerciseId }
            .mapNotNull { it.targetSets }.maxOrNull()
    }

    override suspend fun insertWorkout(row: WorkoutRow) = put(
        store.workouts,
        SyncWorkout(
            id = row.id, startedAt = row.startedAt, endedAt = row.endedAt, routineId = row.routineId,
            programDayId = row.programDayId, updatedAt = clock(), startedBy = row.startedBy,
        ),
    )

    override suspend fun insertWorkoutExercise(row: WorkoutExerciseRow) =
        put(store.workoutExercises, SyncWorkoutExercise(row.id, row.workoutId, row.exerciseId, row.position, clock()))

    override suspend fun insertSet(row: SetRow) =
        put(store.sets, SyncSet(row.id, row.workoutExerciseId, row.position, row.weightKg, row.reps, row.completed, clock()))

    override suspend fun endWorkout(id: String, endedAt: Long) =
        update(store.workouts, id) { it.copy(endedAt = endedAt, updatedAt = clock()) }

    override suspend fun tickSet(id: String, done: Boolean) = update(store.sets, id) { it.copy(completed = done, updatedAt = clock()) }

    override suspend fun setWeight(id: String, weightKg: Double) = update(store.sets, id) { it.copy(weightKg = weightKg, updatedAt = clock()) }

    override suspend fun setReps(id: String, reps: Int) = update(store.sets, id) { it.copy(reps = reps, updatedAt = clock()) }

    override suspend fun deleteSet(id: String) = delete(store.sets, id)

    override suspend fun deleteWorkoutExercise(id: String) {
        store.sets.rows.values.filter { it.row.workoutExerciseId == id }.map { it.row.id }.forEach { delete(store.sets, it) }
        delete(store.workoutExercises, id)
    }

    override suspend fun deleteWorkout(id: String) {
        store.workoutExercises.rows.values.filter { it.row.workoutId == id }.map { it.row.id }.forEach { deleteWorkoutExercise(it) }
        delete(store.workouts, id)
    }

    // --- routines ---

    override suspend fun routine(id: String): RoutineRow? {
        val row = store.routines.get(id) ?: return null
        return RoutineRow(row.id, row.name ?: return null, row.lastUsedAt)
    }

    override suspend fun routineExercise(id: String): RoutineExerciseRow? = store.routineExercises.get(id)?.toRow()

    override suspend fun routineExercises(routineId: String): List<RoutineExerciseRow> =
        store.routineExercises.rows.values.map { it.row }.filter { it.routineId == routineId }
            .mapNotNull { it.toRow() }.sortedBy { it.position }

    override suspend fun insertRoutine(row: RoutineRow) = put(store.routines, SyncRoutine(row.id, row.name, row.lastUsedAt, clock()))

    override suspend fun insertRoutineExercise(row: RoutineExerciseRow) = put(
        store.routineExercises,
        SyncRoutineExercise(row.id, row.routineId, row.exerciseId, row.position, row.targetSets, row.targetReps, clock()),
    )

    override suspend fun renameRoutine(id: String, name: String) = update(store.routines, id) { it.copy(name = name, updatedAt = clock()) }

    override suspend fun markRoutineUsed(routineId: String) {
        val now = clock()
        update(store.routines, routineId) { it.copy(lastUsedAt = now, updatedAt = now) }
    }

    override suspend fun setRoutineExercisePosition(id: String, position: Int) =
        update(store.routineExercises, id) { it.copy(position = position, updatedAt = clock()) }

    override suspend fun setTargetSets(id: String, targetSets: Int) =
        update(store.routineExercises, id) { it.copy(targetSets = targetSets, updatedAt = clock()) }

    override suspend fun setTargetReps(id: String, targetReps: Int?) =
        update(store.routineExercises, id) { it.copy(targetReps = targetReps, updatedAt = clock()) }

    override suspend fun deleteRoutineExercise(id: String) = delete(store.routineExercises, id)

    override suspend fun deleteRoutine(id: String) = delete(store.routines, id)

    // --- programs ---

    override suspend fun program(id: String): ProgramRow? = store.programs.get(id)?.toRow()

    override suspend fun activePrograms(): List<ProgramRow> =
        store.programs.rows.values.map { it.row }.filter { it.isActive == true }.mapNotNull { it.toRow() }

    override suspend fun programDay(id: String): ProgramDayRow? = store.programDays.get(id)?.toRow()

    override suspend fun programDays(programId: String): List<ProgramDayRow> =
        store.programDays.rows.values.map { it.row }.filter { it.programId == programId }.mapNotNull { it.toRow() }.sortedBy { it.position }

    override suspend fun programDaysUsing(routineId: String): List<ProgramDayRow> =
        store.programDays.rows.values.map { it.row }.filter { it.routineId == routineId }.mapNotNull { it.toRow() }.sortedBy { it.position }

    override suspend fun insertProgram(row: ProgramRow) =
        put(store.programs, SyncProgram(row.id, row.name, row.mode.key, row.isActive, clock()))

    override suspend fun insertProgramDay(row: ProgramDayRow) =
        put(store.programDays, SyncProgramDay(row.id, row.programId, row.routineId, row.position, row.weekdays, clock()))

    override suspend fun renameProgram(id: String, name: String) = update(store.programs, id) { it.copy(name = name, updatedAt = clock()) }

    override suspend fun setProgramMode(id: String, mode: ProgramMode) = update(store.programs, id) { it.copy(mode = mode.key, updatedAt = clock()) }

    override suspend fun setProgramActive(id: String, active: Boolean) = update(store.programs, id) { it.copy(isActive = active, updatedAt = clock()) }

    override suspend fun setProgramDayPosition(id: String, position: Int) =
        update(store.programDays, id) { it.copy(position = position, updatedAt = clock()) }

    override suspend fun setProgramDayWeekdays(id: String, weekdays: Int) =
        update(store.programDays, id) { it.copy(weekdays = weekdays, updatedAt = clock()) }

    override suspend fun deleteProgramDay(id: String) = delete(store.programDays, id)

    override suspend fun deleteProgram(id: String) = delete(store.programs, id)
}

// --- wire rows ↔ the rules' rows; a stored live row always has its required fields (TableSpecs) ---

private fun SyncExercise.toRow(): ExerciseRow? {
    val name = name ?: return null
    val group = muscleGroup ?: return null
    return ExerciseRow(id, name, nameKey, MuscleGroup.fromKey(group), Equipment.fromKey(equipment), isCustom ?: false, restSeconds)
}

private fun ExerciseRow.toWire(now: Long) =
    SyncExercise(id, nameKey, name, muscleGroup.key, equipment.key, isCustom, restSeconds, updatedAt = now)

private fun SyncWorkout.toRow(): WorkoutRow? {
    val started = startedAt ?: return null
    return WorkoutRow(id, started, endedAt, routineId, programDayId, startedBy)
}

private fun SyncWorkoutExercise.toRow(): WorkoutExerciseRow? {
    val workout = workoutId ?: return null
    val exercise = exerciseId ?: return null
    val at = position ?: return null
    return WorkoutExerciseRow(id, workout, exercise, at)
}

private fun SyncSet.toRow(): SetRow? {
    val parent = workoutExerciseId ?: return null
    val at = position ?: return null
    val weight = weightKg ?: return null
    val count = reps ?: return null
    val done = completed ?: return null
    return SetRow(id, parent, at, weight, count, done)
}

private fun SyncRoutineExercise.toRow(): RoutineExerciseRow? {
    val routine = routineId ?: return null
    val exercise = exerciseId ?: return null
    val at = position ?: return null
    val target = targetSets ?: return null
    return RoutineExerciseRow(id, routine, exercise, at, target, targetReps)
}

private fun SyncProgram.toRow(): ProgramRow? {
    val name = name ?: return null
    val mode = mode ?: return null
    return ProgramRow(id, name, ProgramMode.fromKey(mode), isActive ?: false)
}

private fun SyncProgramDay.toRow(): ProgramDayRow? {
    val program = programId ?: return null
    val routine = routineId ?: return null
    val at = position ?: return null
    return ProgramDayRow(id, program, routine, at, weekdays ?: 0)
}

/**
 * Core's [ExerciseResolver] in the browser: the exercises in the store (the
 * seeded catalogue, the user's own, extended entries already picked), each
 * with the core catalogue's names in every language from [catalogueNames],
 * so "Bänkpress" resolves in an English browser. The extended catalogue is
 * not shipped to the web yet, so an extended entry with no row is unknown.
 */
class WebExerciseResolver(
    private val store: TungStore,
    private val catalogueNames: () -> Map<String, Map<String, String>>,
) : ExerciseResolver {
    override suspend fun lookupKey(nameKey: String): ExerciseCandidate? =
        store.exercises.rows.values.map { it.row }.firstOrNull { it.nameKey == nameKey }?.let(::candidate)

    override suspend fun lookupId(id: String): ExerciseCandidate? = store.exercises.get(id)?.let(::candidate)

    override suspend fun lookupExtended(fedId: String): ExerciseCandidate? = null

    override suspend fun searchByName(text: String): List<ExerciseCandidate> = store.exercises.rows.values.map { candidate(it.row) }

    private fun candidate(row: SyncExercise): ExerciseCandidate {
        val catalogue = row.nameKey?.let { catalogueNames()[it]?.values }.orEmpty()
        return ExerciseCandidate(
            id = row.id,
            names = (listOfNotNull(row.name) + catalogue).distinct(),
            nameKey = row.nameKey,
            equipment = row.equipment?.let(Equipment::fromKey),
            muscleGroup = row.muscleGroup?.let(MuscleGroup::fromKey),
        )
    }
}
