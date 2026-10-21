package io.github.kanylbullen.tung.ui.exercises

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.ExerciseHistory
import io.github.kanylbullen.tung.core.Muscle
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.catalog.CatalogueIndex
import io.github.kanylbullen.tung.core.catalog.ExercisePageRef
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandHandler
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.data.CatalogueDetails
import io.github.kanylbullen.tung.data.Exercise
import io.github.kanylbullen.tung.data.ExerciseRepository
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.RoutineSummaryRow
import io.github.kanylbullen.tung.data.WorkoutRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

data class ExerciseDetailUiState(
    val loading: Boolean = true,
    /** False when neither Room nor the catalogue knows the ref (a custom exercise deleted elsewhere). */
    val found: Boolean = true,
    val name: String = "",
    val muscleGroup: MuscleGroup? = null,
    val equipment: Equipment? = null,
    val isCustom: Boolean = false,
    val primary: List<Muscle> = emptyList(),
    val secondary: List<Muscle> = emptyList(),
    /** In the phone's language, falling back to English. */
    val instructions: List<String> = emptyList(),
    /** Whether the exercise has a Room row; only then is there a history to show. */
    val inLibrary: Boolean = false,
    val history: ExerciseHistory = ExerciseHistory.EMPTY,
    /** For "Add to routine", most recently used first. */
    val routines: List<RoutineSummaryRow> = emptyList(),
    /** The workout in progress; "Add to workout" shows only when there is one. */
    val workoutInProgress: String? = null,
)

/**
 * The exercise page: what the catalogue says about the exercise (core's
 * [CatalogueIndex] through [CatalogueDetails]: muscles, equipment,
 * instructions), the user's history when it has a Room row, and two actions
 * that are commands: `add_routine_exercise` and `add_exercise`.
 *
 * [ref] is a Room id or an extended entry (`ext:<id>`). An extended entry
 * that has been picked since is a Room row with the id
 * [ExercisePageRef.Extended.exerciseId], so the page finds it either way;
 * one that has not gets its row from the command (the handler's get-or-create).
 */
class ExerciseDetailViewModel(
    private val ref: ExercisePageRef,
    exercises: ExerciseRepository,
    workouts: WorkoutRepository,
    routines: RoutineRepository,
    private val catalogue: CatalogueDetails,
    private val commands: CommandHandler,
    private val displayName: (Exercise) -> String,
) : ViewModel() {

    /** The view model outlives a language change; the screen reports the current locale so the name and the instructions are redone. */
    private val locale = MutableStateFlow(Locale.getDefault())

    /** The Room id this page's exercise has, or will have once added somewhere. */
    private val exerciseId: String = when (ref) {
        is ExercisePageRef.Library -> ref.exerciseId
        is ExercisePageRef.Extended -> ref.exerciseId
    }

    /** The catalogue, null until read; empty if it cannot be. */
    private val index: Flow<CatalogueIndex?> =
        flow<CatalogueIndex?> { emit(catalogue.index()) }
            .catch { emit(CatalogueIndex.EMPTY) }
            .onStart { emit(null) }

    private val row: Flow<Exercise?> =
        exercises.observeExercises().map { all -> all.firstOrNull { it.id == exerciseId } }.distinctUntilChanged()

    private val details: Flow<ExerciseDetailUiState> =
        combine(index, row, locale) { index, row, loc ->
            if (index == null) return@combine ExerciseDetailUiState()
            val entry = index.byExerciseId(exerciseId) ?: index.of(ref)
            if (row == null && entry == null) return@combine ExerciseDetailUiState(loading = false, found = false)
            ExerciseDetailUiState(
                loading = false,
                name = row?.let(displayName) ?: entry?.englishName.orEmpty(),
                muscleGroup = row?.let { MuscleGroup.fromKey(it.muscleGroup) } ?: entry?.muscleGroup,
                equipment = row?.let { Equipment.fromKey(it.equipment) } ?: entry?.equipment,
                isCustom = row?.isCustom == true,
                primary = entry?.muscles?.primary.orEmpty(),
                secondary = entry?.muscles?.secondary.orEmpty(),
                instructions = entry?.instructionsIn(loc.language).orEmpty(),
                inLibrary = row != null,
            )
        }

    val state: StateFlow<ExerciseDetailUiState> =
        combine(
            details,
            workouts.observeExerciseHistory(exerciseId),
            routines.observeRoutines(),
            workouts.observeActiveWorkout(),
        ) { details, history, routineList, active ->
            details.copy(history = history, routines = routineList, workoutInProgress = active?.id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExerciseDetailUiState())

    fun setLocale(value: Locale) {
        locale.value = value
    }

    /** How a command names this exercise: its row once it has one, else the page's ref (an extended entry the handler creates). */
    private fun exerciseRef(): ExerciseRef =
        if (state.value.inLibrary) ExerciseRef.ById(exerciseId) else ref.toExerciseRef()

    /** Appends the exercise to [routineId] with the defaults (3 target sets, no target reps); [onDone] says whether it was added. */
    fun addToRoutine(routineId: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = commands.execute(Command.AddRoutineExercise(routineId, exerciseRef()))
            onDone(result is CommandResult.Done)
        }
    }

    /** Adds the exercise to the workout in progress; [onDone] gets the workout's id to open, only when it was added. */
    fun addToWorkout(onDone: (String) -> Unit) {
        val workoutId = state.value.workoutInProgress ?: return
        viewModelScope.launch {
            val result = commands.execute(Command.AddExercise(workoutId, exerciseRef()))
            if (result is CommandResult.Done) onDone(workoutId)
        }
    }
}
