package io.github.kanylbullen.jarn.data

import io.github.kanylbullen.jarn.domain.Equipment
import io.github.kanylbullen.jarn.domain.MuscleGroup
import kotlinx.coroutines.flow.Flow

class ExerciseRepository(private val dao: ExerciseDao) {

    fun observeExercises(): Flow<List<Exercise>> = dao.observeAll()

    suspend fun createExercise(name: String, muscleGroup: MuscleGroup, equipment: Equipment): Long =
        dao.insert(
            Exercise(
                name = name.trim(),
                muscleGroup = muscleGroup.key,
                equipment = equipment.key,
                isCustom = true,
            )
        )
}
