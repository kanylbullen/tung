package io.github.kanylbullen.tung.web.importing

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.ExtendedExercise
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandHandler
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.extendedExerciseId
import io.github.kanylbullen.tung.core.importing.ImportBundle
import io.github.kanylbullen.tung.core.importing.ImportChoice
import io.github.kanylbullen.tung.core.importing.importedCustomExerciseId
import io.github.kanylbullen.tung.core.newId
import io.github.kanylbullen.tung.core.sync.SyncExercise
import io.github.kanylbullen.tung.core.sync.SyncRoutine
import io.github.kanylbullen.tung.core.sync.SyncRoutineExercise
import io.github.kanylbullen.tung.core.sync.SyncSet
import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.github.kanylbullen.tung.core.sync.SyncWorkoutExercise
import io.github.kanylbullen.tung.web.store.Stored
import io.github.kanylbullen.tung.web.store.Table
import io.github.kanylbullen.tung.web.store.TungStore
import io.github.kanylbullen.tung.web.store.Write

/** What one import wrote; [routineIds] is what "Undo import" deletes besides the batch's workouts. The phone's `ImportResult`. */
data class ImportResult(
    val batchId: String,
    val workoutsAdded: Int,
    val setsAdded: Int,
    val routineIds: List<String>,
    /** Workouts that were here already (an earlier import of the same export): left as they are. */
    val workoutsAlreadyThere: Int,
)

/**
 * The phone's `Importer` over the browser's store: the chosen exercises (an
 * extended entry gets its row, "Create" a custom one with
 * [importedCustomExerciseId]), every workout not here yet with its
 * exercises and sets, stamped `importSource` and one batch id, then the
 * routines; all with the bundle's deterministic ids, dirty and stamped now,
 * and written to IndexedDB in one transaction. A row already here is left
 * alone, so a second import of the same export adds nothing; a tombstone an
 * earlier undo left is removed, so sync brings the row back. This is the one
 * bulk write outside the commands, as on the phone.
 */
class WebImporter(
    private val store: TungStore,
    private val persist: suspend (List<Write>) -> Unit,
    private val extended: () -> List<ExtendedExercise>,
    private val clock: () -> Long,
) {
    suspend fun apply(bundle: ImportBundle, choices: Map<String, ImportChoice>): ImportResult {
        val now = clock()
        val batchId = newId()
        val journal = ArrayList<Write>()
        fun <R : Any> add(table: Table<R>, row: R) {
            val id = table.spec.id(row)
            if (table.rows.containsKey(id)) return
            table.put(row, dirty = true)
            if (store.tombstones.remove(table.table to id) != null) journal += Write.DeleteTombstone(table.table, id)
            journal += Write.Put(table.table, id, table.encode(Stored(row, dirty = true)))
        }

        val entries = extended().associateBy { it.id }
        val exerciseIds = HashMap<String, String>()
        choices.forEach { (name, choice) ->
            exerciseIds[name] = when (choice) {
                is ImportChoice.Use -> {
                    val fedId = choice.exercise.extendedId
                    val entry = fedId?.let { entries[it] }
                    if (entry != null) {
                        add(
                            store.exercises,
                            SyncExercise(extendedExerciseId(entry.id), null, entry.displayName, entry.muscleGroup, entry.equipment, false, null, now),
                        )
                        extendedExerciseId(entry.id)
                    } else {
                        choice.exercise.id
                    }
                }
                ImportChoice.CreateCustom -> {
                    val id = importedCustomExerciseId(bundle.source, name)
                    add(store.exercises, SyncExercise(id, null, name.trim(), MuscleGroup.FULLBODY.key, Equipment.OTHER.key, true, null, now))
                    id
                }
                ImportChoice.Skip -> return@forEach
            }
        }

        var added = 0
        var sets = 0
        var already = 0
        for (workout in bundle.workouts) {
            if (store.workouts.rows.containsKey(workout.id)) {
                already++
                continue
            }
            val kept = workout.exercises.mapNotNull { e -> exerciseIds[e.name]?.let { e to it } }
            if (kept.isEmpty()) continue
            add(
                store.workouts,
                SyncWorkout(workout.id, workout.startedAt, workout.endedAt, updatedAt = now, importSource = bundle.source, importBatchId = batchId),
            )
            kept.forEachIndexed { position, (e, exerciseId) ->
                add(store.workoutExercises, SyncWorkoutExercise(e.id, workout.id, exerciseId, position, now))
                e.sets.forEachIndexed { at, s ->
                    add(store.sets, SyncSet(s.id, e.id, at, s.weightKg, s.reps, true, now))
                    sets++
                }
            }
            added++
        }

        val routineIds = ArrayList<String>()
        for (routine in bundle.routines) {
            if (store.routines.rows.containsKey(routine.id)) continue
            val items = routine.exercises.mapNotNull { e -> exerciseIds[e.name]?.let { e to it } }
            if (items.isEmpty()) continue
            add(store.routines, SyncRoutine(routine.id, routine.name, null, now))
            items.forEachIndexed { position, (e, exerciseId) ->
                add(store.routineExercises, SyncRoutineExercise(e.id, routine.id, exerciseId, position, e.targetSets, e.targetReps, now))
            }
            routineIds += routine.id
        }

        persist(journal)
        store.bump()
        return ImportResult(batchId, added, sets, routineIds, already)
    }
}

/**
 * Undoes an import: every workout of [batchId] goes through `delete_workout`
 * and the routines it made that still exist through one `delete_routines`,
 * so each gets its tombstone as any delete does. Custom exercises stay, as
 * on the phone (there is no delete for exercises). Returns how many
 * workouts it removed.
 */
suspend fun undoImport(store: TungStore, commands: CommandHandler, batchId: String, routineIds: List<String>): Int {
    val workouts = store.workouts.rows.values.map { it.row }.filter { it.importBatchId == batchId }.map { it.id }
    var removed = 0
    workouts.forEach { if (commands.execute(Command.DeleteWorkout(it)) is CommandResult.Done) removed++ }
    val routines = routineIds.filter { store.routines.rows.containsKey(it) }
    if (routines.isNotEmpty()) commands.execute(Command.DeleteRoutines(routines))
    return removed
}
