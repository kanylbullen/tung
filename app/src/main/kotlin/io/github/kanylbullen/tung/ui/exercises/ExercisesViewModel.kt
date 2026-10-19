package io.github.kanylbullen.tung.ui.exercises

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.data.Exercise
import io.github.kanylbullen.tung.data.ExerciseRepository
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.domain.Equipment
import io.github.kanylbullen.tung.domain.MuscleGroup
import io.github.kanylbullen.tung.domain.filterByDisplayName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

/** An exercise with the name the user sees, which is also what the search matches. */
data class NamedExercise(val exercise: Exercise, val displayName: String)

data class ExerciseGroup(val muscleGroup: MuscleGroup, val exercises: List<NamedExercise>)

data class ExercisesUiState(
    val loading: Boolean = true,
    val groups: List<ExerciseGroup> = emptyList(),
) {
    val isEmpty: Boolean get() = groups.isEmpty()
}

/**
 * Backs both the Övningar tab and the picker. With a [workoutId] a tap adds the
 * exercise to that workout; without one the list is just a catalogue.
 *
 * [displayName] gives the name shown on screen (see `ui/common/ExerciseNames.kt`);
 * search and sort both work on it, so "bench" finds Bench press on an English
 * phone and "bank" finds Bänkpress on a Swedish one.
 */
class ExercisesViewModel(
    private val exercises: ExerciseRepository,
    private val workouts: WorkoutRepository,
    private val workoutId: String?,
    private val displayName: (Exercise) -> String,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** The view model outlives a language change; the screen reports the current locale so names are redone. */
    private val locale = MutableStateFlow(Locale.getDefault())

    val isPicker: Boolean get() = workoutId != null

    val state: StateFlow<ExercisesUiState> = combine(exercises.observeExercises(), _query, locale) { all, q, loc ->
        val collator = Collator.getInstance(loc).apply { strength = Collator.SECONDARY }
        val byGroup = filterByDisplayName(all.map { it to displayName(it) }, q)
            .map { (exercise, name) -> NamedExercise(exercise, name) }
            .groupBy { MuscleGroup.fromKey(it.exercise.muscleGroup) }
        ExercisesUiState(
            loading = false,
            groups = MuscleGroup.entries.mapNotNull { group ->
                byGroup[group]?.let { list -> ExerciseGroup(group, list.sortedWith(compareBy(collator) { it.displayName })) }
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExercisesUiState())

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setLocale(value: Locale) {
        locale.value = value
    }

    fun pick(exerciseId: String, onDone: () -> Unit) {
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
