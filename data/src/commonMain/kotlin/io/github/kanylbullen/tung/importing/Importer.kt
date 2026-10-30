package io.github.kanylbullen.tung.importing

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.ExtendedCatalog
import io.github.kanylbullen.tung.core.ExtendedExercise
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.importing.ImportBundle
import io.github.kanylbullen.tung.core.importing.ImportChoice
import io.github.kanylbullen.tung.core.importing.importedCustomExerciseId
import io.github.kanylbullen.tung.core.newId
import io.github.kanylbullen.tung.core.sync.SyncTable
import io.github.kanylbullen.tung.data.systemMillis
import io.github.kanylbullen.tung.data.Exercise
import io.github.kanylbullen.tung.data.ExerciseRepository
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.SetEntry
import io.github.kanylbullen.tung.data.TungDatabase
import io.github.kanylbullen.tung.data.Workout
import io.github.kanylbullen.tung.data.WorkoutExercise
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.data.transaction

/** What one import wrote; [routineIds] is what "Undo import" deletes besides the batch's workouts. */
data class ImportResult(
    val batchId: String,
    val workoutsAdded: Int,
    val setsAdded: Int,
    val routineIds: List<String>,
    /** Workouts that were here already (an earlier import of the same export): left as they are. */
    val workoutsAlreadyThere: Int,
    /** Workouts with every exercise skipped in the review. */
    val workoutsEmpty: Int,
    val customExercisesCreated: Int,
)

/**
 * Writes an [ImportBundle] into Room (CLAUDE.md, "Import"). One transaction:
 * the exercises the review chose (an extended entry gets its row, a custom
 * one is created with [importedCustomExerciseId]), then every workout that is
 * not here yet with its exercises and sets, stamped `importSource` and one
 * `importBatchId`, then the routines through `RoutineRepository.createFromPlan`,
 * the same path as `save_routine_plan`. Every insert uses the bundle's
 * deterministic ids with `IGNORE`, so a second import of the same export adds
 * nothing. Rows get `updatedAt` now and are dirty, like every write; a
 * tombstone left by an earlier undo of the same rows is removed, so sync
 * brings them back.
 *
 * This is the one bulk write outside `container.commands`: no command
 * expresses "a whole history", and the server's import API (later) will call
 * the same core adapter.
 */
class Importer(
    private val db: TungDatabase,
    private val workouts: WorkoutRepository,
    private val exercises: ExerciseRepository,
    private val routines: RoutineRepository,
    private val extendedCatalog: ExtendedCatalog,
    private val clock: () -> Long = ::systemMillis,
) {

    /**
     * Applies [bundle] with [choices] for its exercise names (a name with no
     * choice, or [ImportChoice.Skip], is left out with its sets and routine rows).
     */
    suspend fun apply(bundle: ImportBundle, choices: Map<String, ImportChoice>): ImportResult {
        val wanted = choices.values.filterIsInstance<ImportChoice.Use>().mapNotNull { it.exercise.extendedId }.toSet()
        val entries: Map<String, ExtendedExercise> =
            if (wanted.isEmpty()) emptyMap() else extendedCatalog.all().filter { it.id in wanted }.associateBy { it.id }
        val batchId = newId()
        return db.transaction {
            val now = clock()
            var customCreated = 0
            val exerciseIds = HashMap<String, String>()
            choices.forEach { (name, choice) ->
                exerciseIds[name] = when (choice) {
                    is ImportChoice.Use -> choice.exercise.extendedId?.let { exercises.getOrCreateExtended(entries.getValue(it)) } ?: choice.exercise.id
                    ImportChoice.CreateCustom -> {
                        val id = importedCustomExerciseId(bundle.source, name)
                        if (db.exerciseDao().getById(id) == null) {
                            db.exerciseDao().insertIfAbsent(
                                Exercise(
                                    id = id,
                                    name = name.trim(),
                                    muscleGroup = MuscleGroup.FULLBODY.key,
                                    equipment = Equipment.OTHER.key,
                                    isCustom = true,
                                    updatedAt = now,
                                ),
                            )
                            customCreated++
                        }
                        id
                    }
                    ImportChoice.Skip -> return@forEach
                }
            }

            val dao = db.workoutDao()
            val existing = bundle.workouts.map { it.id }.chunked(SQL_CHUNK).flatMap { dao.existingWorkoutIds(it) }.toSet()
            var added = 0
            var sets = 0
            var empty = 0
            val writtenWorkouts = mutableListOf<String>()
            val writtenExercises = mutableListOf<String>()
            val writtenSets = mutableListOf<String>()
            for (workout in bundle.workouts) {
                if (workout.id in existing) continue
                val kept = workout.exercises.mapNotNull { e -> exerciseIds[e.name]?.let { e to it } }
                if (kept.isEmpty()) {
                    empty++
                    continue
                }
                dao.insertWorkoutIfAbsent(
                    Workout(
                        id = workout.id,
                        startedAt = workout.startedAt,
                        endedAt = workout.endedAt,
                        updatedAt = now,
                        importSource = bundle.source,
                        importBatchId = batchId,
                    ),
                )
                dao.insertWorkoutExercisesIfAbsent(
                    kept.mapIndexed { position, (e, exerciseId) ->
                        WorkoutExercise(id = e.id, workoutId = workout.id, exerciseId = exerciseId, position = position, updatedAt = now)
                    },
                )
                val rows = kept.flatMap { (e, _) ->
                    e.sets.mapIndexed { position, s ->
                        SetEntry(
                            id = s.id,
                            workoutExerciseId = e.id,
                            position = position,
                            weightKg = s.weightKg,
                            reps = s.reps,
                            completed = true,
                            updatedAt = now,
                        )
                    }
                }
                dao.insertSetsIfAbsent(rows)
                added++
                sets += rows.size
                writtenWorkouts += workout.id
                writtenExercises += kept.map { it.first.id }
                writtenSets += rows.map { it.id }
            }

            val routineIds = mutableListOf<String>()
            val writtenRoutineExercises = mutableListOf<String>()
            for (routine in bundle.routines) {
                if (db.routineDao().getRoutine(routine.id) != null) continue
                val items = routine.exercises.mapNotNull { e -> exerciseIds[e.name]?.let { e to it } }
                if (items.isEmpty()) continue
                routines.createFromPlan(
                    routine.name,
                    items.map { (e, exerciseId) -> Triple(exerciseId, e.targetSets, e.targetReps) },
                    routineId = routine.id,
                    routineExerciseIds = items.map { it.first.id },
                )
                routineIds += routine.id
                writtenRoutineExercises += items.map { it.first.id }
            }

            clearTombstones(SyncTable.WORKOUTS, writtenWorkouts)
            clearTombstones(SyncTable.WORKOUT_EXERCISES, writtenExercises)
            clearTombstones(SyncTable.SETS, writtenSets)
            clearTombstones(SyncTable.ROUTINES, routineIds)
            clearTombstones(SyncTable.ROUTINE_EXERCISES, writtenRoutineExercises)

            ImportResult(
                batchId = batchId,
                workoutsAdded = added,
                setsAdded = sets,
                routineIds = routineIds,
                workoutsAlreadyThere = existing.size,
                workoutsEmpty = empty,
                customExercisesCreated = customCreated,
            )
        }
    }

    /**
     * Undoes one import: deletes the workouts of [batchId] and the routines it
     * created ([routineIds]) through the ordinary deletes, with tombstones, in
     * one transaction. Custom exercises it created stay (there is no delete path
     * for exercises).
     */
    suspend fun undo(batchId: String, routineIds: List<String>): Int = db.transaction {
        val removed = workouts.deleteImportBatch(batchId)
        routineIds.forEach { routines.delete(it) }
        removed
    }

    private suspend fun clearTombstones(table: SyncTable, ids: List<String>) =
        ids.chunked(SQL_CHUNK).forEach { db.tombstoneDao().deleteAll(table.key, it) }

    private companion object {
        /** SQLite allows 999 bound variables per statement. */
        const val SQL_CHUNK = 500
    }
}
