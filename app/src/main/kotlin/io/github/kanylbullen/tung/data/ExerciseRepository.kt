package io.github.kanylbullen.tung.data

import io.github.kanylbullen.tung.domain.Equipment
import io.github.kanylbullen.tung.domain.MuscleGroup
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
