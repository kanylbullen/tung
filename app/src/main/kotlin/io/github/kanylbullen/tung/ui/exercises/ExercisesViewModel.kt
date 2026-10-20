package io.github.kanylbullen.tung.ui.exercises

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.ExtendedCatalog
import io.github.kanylbullen.tung.core.ExtendedExercise
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.MuscleIndex
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandHandler
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.command.id
import io.github.kanylbullen.tung.core.filterByDisplayName
import io.github.kanylbullen.tung.core.normalizeForSearch
import io.github.kanylbullen.tung.core.picker.Candidate
import io.github.kanylbullen.tung.core.picker.HistoricWorkout
import io.github.kanylbullen.tung.core.picker.SuggestionReason
import io.github.kanylbullen.tung.core.picker.suggest
import io.github.kanylbullen.tung.data.CatalogueMuscles
import io.github.kanylbullen.tung.data.Exercise
import io.github.kanylbullen.tung.data.ExerciseRepository
import io.github.kanylbullen.tung.data.WorkoutRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

/** An exercise with the name the user sees, which is also what the search matches. */
data class NamedExercise(val exercise: Exercise, val displayName: String)

data class ExerciseGroup(val muscleGroup: MuscleGroup, val exercises: List<NamedExercise>)

/**
 * A row of the picker's "Suggested" section: the exercise, why (core's
 * [SuggestionReason]) and, for [SuggestionReason.OftenAfter], the displayed
 * name of the exercise it follows.
 */
data class SuggestedExercise(val exercise: NamedExercise, val reason: SuggestionReason, val afterName: String? = null)

/** What the suggestions need besides the live lists, read once when the picker opens. */
private class SuggestionInputs(val history: List<HistoricWorkout>, val muscles: MuscleIndex)

data class ExercisesUiState(
    val loading: Boolean = true,
    val groups: List<ExerciseGroup> = emptyList(),
    /** Picker only: matches from the extended catalogue, shown under "More exercises". */
    val more: List<ExtendedExercise> = emptyList(),
    /** Picker only, with an empty search and a workout that has exercises: the "Suggested" section. */
    val suggested: List<SuggestedExercise> = emptyList(),
) {
    val isEmpty: Boolean get() = groups.isEmpty() && more.isEmpty()
}

/**
 * Backs both the Exercises tab and the picker. With a [workoutId] a tap adds the
 * exercise to that workout; without one the list is just a catalogue.
 *
 * [displayName] gives the name shown on screen (see `ui/common/ExerciseNames.kt`);
 * search and sort both work on it, so "bench" finds Bench press on an English
 * phone and "bank" finds Bänkpress on a Swedish one.
 *
 * In the picker, a query of [ExtendedCatalog.MIN_QUERY_LENGTH] characters or
 * more also searches the extended catalogue ([ExercisesUiState.more], English
 * names). The tab never shows it.
 *
 * In the picker, while the search is empty and the workout already has
 * exercises, [ExercisesUiState.suggested] holds up to six suggestions from
 * `core.picker.suggest`: the user's own history ([workouts], read once when
 * the picker opens) and the catalogue's muscles ([catalogueMuscles]).
 */
class ExercisesViewModel(
    exercises: ExerciseRepository,
    private val commands: CommandHandler,
    private val extendedCatalog: ExtendedCatalog,
    private val workoutId: String?,
    private val workouts: WorkoutRepository,
    private val catalogueMuscles: CatalogueMuscles,
    private val now: () -> Long,
    private val displayName: (Exercise) -> String,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** The view model outlives a language change; the screen reports the current locale so names are redone. */
    private val locale = MutableStateFlow(Locale.getDefault())

    val isPicker: Boolean get() = workoutId != null

    /**
     * The extended catalogue, read the first time the picker's query is long
     * enough to search it; empty before that, in the tab, and if it cannot be read.
     */
    private val extended: Flow<List<ExtendedExercise>> =
        if (workoutId == null) {
            flowOf(emptyList())
        } else {
            _query.filter { normalizeForSearch(it).length >= ExtendedCatalog.MIN_QUERY_LENGTH }
                .take(1)
                .map { extendedCatalog.all() }
                .catch { emit(emptyList()) }
                .onStart { emit(emptyList()) }
        }

    /** Picker only: history and muscles, null until read and if they cannot be. */
    private val suggestionInputs: Flow<SuggestionInputs?> =
        if (workoutId == null) {
            flowOf(null)
        } else {
            flow<SuggestionInputs?> { emit(SuggestionInputs(workouts.recentHistory(), catalogueMuscles.index())) }
                .catch { emit(null) }
                .onStart { emit(null) }
        }

    /** Picker only: the suggestions for the workout as it is now, in the current language. */
    private val suggestions: Flow<List<SuggestedExercise>> =
        if (workoutId == null) {
            flowOf(emptyList())
        } else {
            combine(exercises.observeExercises(), workouts.observeExercises(workoutId), suggestionInputs, locale) { all, current, inputs, _ ->
                if (inputs == null || current.isEmpty()) return@combine emptyList()
                val byId = all.associateBy { it.id }
                val names = HashMap<String, String>()
                fun nameOf(exercise: Exercise) = names.getOrPut(exercise.id) { displayName(exercise) }
                suggest(
                    currentWorkout = current.map { ExerciseRef.ById(it.workoutExercise.exerciseId) },
                    history = inputs.history,
                    candidates = all.map { Candidate(it.id, nameOf(it), MuscleGroup.fromKey(it.muscleGroup)) },
                    muscles = inputs.muscles,
                    now = now(),
                ).mapNotNull { suggestion ->
                    val exercise = byId[suggestion.exerciseId] ?: return@mapNotNull null
                    val reason = suggestion.reason
                    val after = (reason as? SuggestionReason.OftenAfter)?.let { byId[it.exerciseId] }?.let(::nameOf)
                    SuggestedExercise(NamedExercise(exercise, nameOf(exercise)), reason, after)
                }
            }
        }

    private val lists: Flow<ExercisesUiState> =
        combine(exercises.observeExercises(), _query, locale, extended) { all, q, loc, ext ->
            val collator = Collator.getInstance(loc).apply { strength = Collator.SECONDARY }
            val byGroup = filterByDisplayName(all.map { it to displayName(it) }, q)
                .map { (exercise, name) -> NamedExercise(exercise, name) }
                .groupBy { MuscleGroup.fromKey(it.exercise.muscleGroup) }
            val existingIds = all.mapTo(HashSet()) { it.id }
            ExercisesUiState(
                loading = false,
                groups = MuscleGroup.entries.mapNotNull { group ->
                    byGroup[group]?.let { list -> ExerciseGroup(group, list.sortedWith(compareBy(collator) { it.displayName })) }
                },
                more = ExtendedCatalog.search(ext, existingIds, q).sortedWith(compareBy(collator) { it.displayName }),
            )
        }

    val state: StateFlow<ExercisesUiState> =
        combine(lists, suggestions, _query) { lists, suggested, q ->
            // Typing hides the section: search filters everything, as without suggestions.
            if (q.isBlank()) lists.copy(suggested = suggested) else lists
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
            commands.execute(Command.AddExercise(id, ExerciseRef.ById(exerciseId)))
            onDone()
        }
    }

    /** Picker only: adds an extended-catalogue exercise, creating its Room row the first time. */
    fun pickExtended(entry: ExtendedExercise, onDone: () -> Unit) {
        val id = workoutId ?: return
        viewModelScope.launch {
            commands.execute(Command.AddExercise(id, ExerciseRef.Extended(entry.id)))
            onDone()
        }
    }

    /** Creates a custom exercise; in picker mode it also goes straight into the workout. */
    fun create(name: String, muscleGroup: MuscleGroup, equipment: Equipment, onDone: (addedToWorkout: Boolean) -> Unit) {
        viewModelScope.launch {
            val newId = commands.execute(Command.CreateExercise(name, muscleGroup, equipment)).id(CommandIds.EXERCISE_ID)
            val target = workoutId
            if (target != null && newId != null) commands.execute(Command.AddExercise(target, ExerciseRef.ById(newId)))
            _query.value = ""
            onDone(target != null)
        }
    }
}
