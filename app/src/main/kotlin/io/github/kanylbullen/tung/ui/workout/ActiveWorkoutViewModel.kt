package io.github.kanylbullen.tung.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.data.WorkoutExerciseWithSets
import io.github.kanylbullen.tung.data.WorkoutRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveWorkoutViewModel(
    private val workoutId: String,
    private val repo: WorkoutRepository,
) : ViewModel() {

    /**
     * Last session's sets per exercise. History cannot change while a workout
     * is in progress, so each exercise is looked up once.
     */
    private val previousByExercise = mutableMapOf<String, List<LoggedSet>>()
    private val finishing = MutableStateFlow(false)

    private val exercisesWithPrevious: Flow<Pair<List<WorkoutExerciseWithSets>, Map<String, List<LoggedSet>>>> =
        repo.observeExercises(workoutId).mapLatest { list ->
            list.map { it.exercise.id }.distinct()
                .filterNot { it in previousByExercise }
                .forEach { id -> previousByExercise[id] = repo.previousSessionSets(id, workoutId) }
            list to previousByExercise.toMap()
        }

    val state: StateFlow<ActiveWorkoutUiState> = combine(
        repo.observeWorkout(workoutId),
        exercisesWithPrevious,
        finishing,
    ) { workout, (exercises, previous), isFinishing ->
        when {
            workout == null && isFinishing -> ActiveWorkoutUiState(loading = true)
            workout == null -> ActiveWorkoutUiState(loading = false, missing = true)
            else -> ActiveWorkoutUiState(
                loading = false,
                startedAt = workout.startedAt,
                exercises = exercises.map { e ->
                    val last = previous[e.exercise.id].orEmpty()
                    ActiveExerciseUi(
                        workoutExerciseId = e.workoutExercise.id,
                        exerciseId = e.exercise.id,
                        name = e.exercise.name,
                        nameKey = e.exercise.nameKey,
                        sets = e.sortedSets.mapIndexed { index, s ->
                            ActiveSetUi(
                                id = s.id,
                                number = index + 1,
                                weightKg = s.weightKg,
                                reps = s.reps,
                                completed = s.completed,
                                previous = last.getOrNull(index),
                            )
                        },
                    )
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActiveWorkoutUiState())

    private val eventChannel = Channel<ActiveWorkoutEvent>(Channel.BUFFERED)
    val events: Flow<ActiveWorkoutEvent> = eventChannel.receiveAsFlow()

    fun addSet(workoutExerciseId: String) = launch { repo.addSet(workoutExerciseId) }

    fun setWeight(setId: String, weightKg: Double) = launch { repo.updateWeight(setId, weightKg) }

    fun setReps(setId: String, reps: Int) = launch { repo.updateReps(setId, reps) }

    fun setCompleted(setId: String, completed: Boolean) = launch { repo.setCompleted(setId, completed) }

    fun deleteSet(setId: String) = launch { repo.deleteSet(setId) }

    fun removeExercise(workoutExerciseId: String) = launch { repo.removeExercise(workoutExerciseId) }

    fun finish() = launch {
        finishing.value = true
        val saved = repo.finishWorkout(workoutId)
        eventChannel.send(ActiveWorkoutEvent.Finished(workoutId, saved))
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
