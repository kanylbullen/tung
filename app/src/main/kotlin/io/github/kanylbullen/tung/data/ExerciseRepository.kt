package io.github.kanylbullen.tung.data

import io.github.kanylbullen.tung.domain.Equipment
import io.github.kanylbullen.tung.domain.MuscleGroup
import kotlinx.coroutines.flow.Flow

class ExerciseRepository(
    private val dao: ExerciseDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun observeExercises(): Flow<List<Exercise>> = dao.observeAll()

    /** Creates a custom exercise and returns its id. */
    suspend fun createExercise(name: String, muscleGroup: MuscleGroup, equipment: Equipment): String {
        val exercise = Exercise(
            id = newId(),
            name = name.trim(),
            muscleGroup = muscleGroup.key,
            equipment = equipment.key,
            isCustom = true,
            updatedAt = clock(),
        )
        dao.insert(exercise)
        return exercise.id
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
}
