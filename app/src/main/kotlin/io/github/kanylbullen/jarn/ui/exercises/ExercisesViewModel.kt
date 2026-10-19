package io.github.kanylbullen.jarn.ui.exercises

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.jarn.data.Exercise
import io.github.kanylbullen.jarn.data.ExerciseRepository
import io.github.kanylbullen.jarn.data.WorkoutRepository
import io.github.kanylbullen.jarn.domain.Equipment
import io.github.kanylbullen.jarn.domain.MuscleGroup
import io.github.kanylbullen.jarn.domain.matchesSearch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

data class ExerciseGroup(val muscleGroup: MuscleGroup, val exercises: List<Exercise>)

data class ExercisesUiState(
    val loading: Boolean = true,
    val groups: List<ExerciseGroup> = emptyList(),
) {
    val isEmpty: Boolean get() = groups.isEmpty()
}

/**
 * Backs both the Övningar tab and the picker. With a [workoutId] a tap adds the
 * exercise to that workout; without one the list is just a catalogue.
 */
class ExercisesViewModel(
    private val exercises: ExerciseRepository,
    private val workouts: WorkoutRepository,
    private val workoutId: Long?,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val isPicker: Boolean get() = workoutId != null

    private val collator: Collator = Collator.getInstance(Locale.getDefault()).apply {
        strength = Collator.SECONDARY
    }

    val state: StateFlow<ExercisesUiState> = combine(exercises.observeExercises(), _query) { all, q ->
        val byGroup = all.filter { matchesSearch(it.name, q) }
            .groupBy { MuscleGroup.fromKey(it.muscleGroup) }
        ExercisesUiState(
            loading = false,
            groups = MuscleGroup.entries.mapNotNull { group ->
                byGroup[group]?.let { list -> ExerciseGroup(group, list.sortedWith(compareBy(collator) { it.name })) }
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExercisesUiState())

    fun setQuery(value: String) {
        _query.value = value
    }

    fun pick(exerciseId: Long, onDone: () -> Unit) {
        val id = workoutId ?: return
        viewModelScope.launch {
            workouts.addExercise(id, exerciseId)
            onDone()
        }
    }

    /** Creates a custom exercise; in picker mode it also goes straight into the workout. */
    fun create(name: String, muscleGroup: MuscleGroup, equipment: Equipment, onDone: (addedToWorkout: Boolean) -> Unit) {
        viewModelScope.launch {
            val newId = exercises.createExercise(name, muscleGroup, equipment)
            val target = workoutId
            if (target != null) workouts.addExercise(target, newId)
            _query.value = ""
            onDone(target != null)
        }
    }
}
