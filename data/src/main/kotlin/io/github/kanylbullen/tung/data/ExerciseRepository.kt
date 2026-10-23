package io.github.kanylbullen.tung.data

import androidx.room.withTransaction
import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.ExtendedExercise
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.command.CommandRules
import io.github.kanylbullen.tung.core.command.CommandIds.EXERCISE_ID
import io.github.kanylbullen.tung.core.command.id
import io.github.kanylbullen.tung.core.extendedExerciseId
import kotlinx.coroutines.flow.Flow

/**
 * Exercise reads, the get-or-create of an extended-catalogue entry's row,
 * and the Room-side entry to the exercise writes for callers that are not
 * commands (tests): each runs core's rule ([CommandRules], issue #87)
 * over this database, as `AppCommandHandler` does for the commands.
 */
class ExerciseRepository(
    private val db: TungDatabase,
    private val dao: ExerciseDao = db.exerciseDao(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val executor = CommandRules()

    fun observeExercises(): Flow<List<Exercise>> = dao.observeAll()

    /** Creates a custom exercise ([CommandRules.createExercise]) and returns its id. */
    suspend fun createExercise(name: String, muscleGroup: MuscleGroup, equipment: Equipment): String = db.withTransaction {
        checkNotNull(executor.createExercise(RoomCommandTx(db, clock), name, muscleGroup, equipment).id(EXERCISE_ID))
    }

    /**
     * Returns the id of the Room row for an extended-catalogue exercise, creating
     * it the first time it is picked. The id is [extendedExerciseId], so picking
     * the same exercise again, or on another device, gives the same row.
     */
    suspend fun getOrCreateExtended(entry: ExtendedExercise): String {
        val id = extendedExerciseId(entry.id)
        dao.insertIfAbsent(
            Exercise(
                id = id,
                name = entry.displayName,
                nameKey = null,
                muscleGroup = MuscleGroup.fromKey(entry.muscleGroup).key,
                equipment = Equipment.fromKey(entry.equipment).key,
                isCustom = false,
                updatedAt = clock(),
            ),
        )
        return id
    }

    /**
     * The rest timer for [exerciseId] ([CommandRules.setRest]): seconds
     * between sets, 0 for none, null to follow the default. An ordinary update,
     * catalogue rows included, so `updatedAt` moves; the same value again
     * writes nothing.
     */
    suspend fun setRestSeconds(exerciseId: String, restSeconds: Int?) = db.withTransaction {
        executor.setRest(RoomCommandTx(db, clock), exerciseId, restSeconds)
        Unit
    }
}
