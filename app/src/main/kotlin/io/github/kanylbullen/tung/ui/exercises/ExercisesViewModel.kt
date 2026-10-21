package io.github.kanylbullen.tung.ui.exercises

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.ExtendedCatalog
import io.github.kanylbullen.tung.core.ExtendedExercise
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.MuscleIndex
import io.github.kanylbullen.tung.core.catalog.BrowseItem
import io.github.kanylbullen.tung.core.catalog.BrowseSection
import io.github.kanylbullen.tung.core.catalog.EquipmentFilter
import io.github.kanylbullen.tung.core.catalog.LibraryExercise
import io.github.kanylbullen.tung.core.catalog.browse
import io.github.kanylbullen.tung.core.catalog.browseGroups
import io.github.kanylbullen.tung.core.catalog.browseItems
import io.github.kanylbullen.tung.core.catalog.browseNameOrder
import io.github.kanylbullen.tung.core.catalog.filterByEquipment
import io.github.kanylbullen.tung.core.catalog.passes
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
import io.github.kanylbullen.tung.data.EquipmentSettings
import io.github.kanylbullen.tung.data.Exercise
import io.github.kanylbullen.tung.data.ExerciseRepository
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.WorkoutRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

/**
 * What the picker adds to: the workout in progress ([Workout], `add_exercise`)
 * or a saved routine ([Routine], `add_routine_exercise`). Null is the
 * Exercises tab, which lists the catalogue and opens the exercise page.
 */
sealed interface PickerTarget {
    data class Workout(val workoutId: String) : PickerTarget

    data class Routine(val routineId: String) : PickerTarget

    /** Only choose: the tap hands the exercise back ([ExercisesScreen]'s `onChosen`) and changes nothing. The import's review uses it. */
    data object Choose : PickerTarget
}

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
    /** Tab only: the whole catalogue (core `browse`), in muscle-group sections, filtered by [selectedGroup] and the search. */
    val browse: List<BrowseSection> = emptyList(),
    /** Tab only: the group chips after "All", the groups that have exercises. */
    val chips: List<MuscleGroup> = emptyList(),
    /** Tab only: the chip that is on; null is "All". */
    val selectedGroup: MuscleGroup? = null,
    /** "My equipment": what the lists and the suggestions keep to ([EquipmentFilter.ALL] when not narrowed). */
    val equipment: Set<Equipment> = EquipmentFilter.ALL,
) {
    val isEmpty: Boolean get() = groups.isEmpty() && more.isEmpty() && browse.isEmpty()
}

/**
 * Backs both the Exercises tab and the picker. With a [target] a tap adds the
 * exercise to that workout or routine; without one a tap opens its page.
 *
 * [displayName] gives the name shown on screen (see `ui/common/ExerciseNames.kt`);
 * search and sort both work on it, so "bench" finds Bench press on an English
 * phone and "bank" finds Bänkpress on a Swedish one.
 *
 * In the picker, a query of [ExtendedCatalog.MIN_QUERY_LENGTH] characters or
 * more also searches the extended catalogue ([ExercisesUiState.more], English
 * names).
 *
 * The tab lists the whole catalogue instead ([ExercisesUiState.browse]): the
 * user's exercises and every extended entry not picked yet, read when the tab
 * opens, in muscle-group sections with a chip per group. The grouping,
 * sorting and filtering are core's (`core.catalog.browse`); a row opens the
 * exercise page rather than adding anything.
 *
 * In the picker for a workout, while the search is empty and the workout already has
 * exercises, [ExercisesUiState.suggested] holds up to six suggestions from
 * `core.picker.suggest`: the user's own history ([workouts], read once when
 * the picker opens) and the catalogue's muscles ([catalogueMuscles]).
 *
 * "My equipment" ([equipmentSettings], core's `catalog.EquipmentFilter`)
 * narrows the tab, the picker's list, "More exercises" and Suggested to the
 * exercises done with what the user has; in the picker the exercises already
 * in the workout or routine always show. Choosing for the import's review
 * ([PickerTarget.Choose]) is not narrowed: it maps history, done with
 * whatever equipment there was.
 */
class ExercisesViewModel(
    exercises: ExerciseRepository,
    private val commands: CommandHandler,
    private val extendedCatalog: ExtendedCatalog,
    private val target: PickerTarget?,
    private val workouts: WorkoutRepository,
    private val catalogueMuscles: CatalogueMuscles,
    private val now: () -> Long,
    private val equipmentSettings: EquipmentSettings,
    private val routines: RoutineRepository,
    private val displayName: (Exercise) -> String,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Tab only: the group chip that is on; null is "All". */
    private val group = MutableStateFlow<MuscleGroup?>(null)

    /** The view model outlives a language change; the screen reports the current locale so names are redone. */
    private val locale = MutableStateFlow(Locale.getDefault())

    val isPicker: Boolean get() = target != null

    /** "My equipment"; everything for [PickerTarget.Choose], which is not narrowed. */
    private val available: Flow<Set<Equipment>> =
        if (target == PickerTarget.Choose) flowOf(EquipmentFilter.ALL) else equipmentSettings.available

    /** The screen's chip and sheet read and change "My equipment" here. */
    val equipment: StateFlow<Set<Equipment>> =
        available.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EquipmentFilter.ALL)

    /** Whether the screen shows the equipment chip: the tab and the pickers that add, not the import's chooser. */
    val filtersEquipment: Boolean get() = target != PickerTarget.Choose

    /** Picker only: the exercise ids already in the workout or routine, which the equipment filter never hides. */
    private val inTarget: Flow<Set<String>> = when (val t = target) {
        is PickerTarget.Workout -> workouts.observeExercises(t.workoutId).map { list -> list.map { it.workoutExercise.exerciseId }.toSet() }
        is PickerTarget.Routine -> routines.observeRoutineExercises(t.routineId).map { list -> list.map { it.exercise.id }.toSet() }
        PickerTarget.Choose, null -> flowOf(emptySet<String>())
    }.distinctUntilChanged()

    /** Suggestions are about the workout in progress ("Not trained yet today"), so only that picker has them. */
    private val workoutId: String? = (target as? PickerTarget.Workout)?.workoutId

    /**
     * The extended catalogue, read the first time the picker's query is long
     * enough to search it; empty before that, in the tab, and if it cannot be read.
     */
    private val extended: Flow<List<ExtendedExercise>> =
        if (target == null) {
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
            combine(exercises.observeExercises(), workouts.observeExercises(workoutId), suggestionInputs, locale, available) { all, current, inputs, _, have ->
                if (inputs == null || current.isEmpty()) return@combine emptyList()
                val byId = all.associateBy { it.id }
                val names = HashMap<String, String>()
                fun nameOf(exercise: Exercise) = names.getOrPut(exercise.id) { displayName(exercise) }
                val currentIds = current.mapTo(HashSet()) { it.workoutExercise.exerciseId }
                // The workout's own exercises stay candidates (core reads their groups); suggest never proposes them.
                val candidates = filterByEquipment(all, have, currentIds, { it.id }, { Equipment.fromKey(it.equipment) })
                suggest(
                    currentWorkout = current.map { ExerciseRef.ById(it.workoutExercise.exerciseId) },
                    history = inputs.history,
                    candidates = candidates.map { Candidate(it.id, nameOf(it), MuscleGroup.fromKey(it.muscleGroup)) },
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

    private val equipmentAndTarget: Flow<Pair<Set<Equipment>, Set<String>>> = combine(available, inTarget) { have, ids -> have to ids }

    private val lists: Flow<ExercisesUiState> =
        combine(exercises.observeExercises(), _query, locale, extended, equipmentAndTarget) { all, q, loc, ext, (have, ids) ->
            val collator = Collator.getInstance(loc).apply { strength = Collator.SECONDARY }
            val shown = filterByEquipment(all, have, ids, { it.id }, { Equipment.fromKey(it.equipment) })
            val byGroup = filterByDisplayName(shown.map { it to displayName(it) }, q)
                .map { (exercise, name) -> NamedExercise(exercise, name) }
                .groupBy { MuscleGroup.fromKey(it.exercise.muscleGroup) }
            val existingIds = all.mapTo(HashSet()) { it.id }
            ExercisesUiState(
                loading = false,
                groups = MuscleGroup.entries.mapNotNull { group ->
                    byGroup[group]?.let { list -> ExerciseGroup(group, list.sortedWith(compareBy(collator) { it.displayName })) }
                },
                more = ExtendedCatalog.search(ext, existingIds, q)
                    .filter { passes(it.equipment, have) }
                    .sortedWith(compareBy(collator) { it.displayName }),
                equipment = have,
            )
        }

    /** Tab only: the whole extended catalogue, read when the tab opens; empty until then and if it cannot be read. */
    private val tabCatalogue: Flow<List<ExtendedExercise>> =
        flow { emit(extendedCatalog.all()) }
            .catch { emit(emptyList()) }
            .onStart { emit(emptyList()) }

    /** Tab only: every row, named in the current language; recomputed when Room, the catalogue or the language changes, not per keystroke. */
    private val browseRows: Flow<List<BrowseItem>> =
        combine(exercises.observeExercises(), tabCatalogue, locale) { all, ext, _ ->
            browseItems(
                all.map { LibraryExercise(it.id, displayName(it), it.muscleGroup, it.equipment, it.isCustom) },
                ext,
            )
        }

    private val browseState: Flow<ExercisesUiState> =
        combine(browseRows, _query, group, locale, available) { items, q, g, loc, have ->
            ExercisesUiState(
                loading = false,
                browse = browse(items, g, q, browseNameOrder(loc), have),
                chips = browseGroups(items, have),
                selectedGroup = g,
                equipment = have,
            )
        }

    val state: StateFlow<ExercisesUiState> =
        if (target == null) {
            browseState.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExercisesUiState())
        } else {
            combine(lists, suggestions, _query) { lists, suggested, q ->
                // Typing hides the section: search filters everything, as without suggestions.
                if (q.isBlank()) lists.copy(suggested = suggested) else lists
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExercisesUiState())
        }

    /** Tab only: turns a group chip on; [value] null, or the chip that is already on, is "All". */
    fun setGroup(value: MuscleGroup?) {
        group.value = if (value == group.value) null else value
    }

    /** Saves "My equipment" straight away; the lists follow the DataStore. Never empty: the sheet keeps one ticked. */
    fun setEquipment(value: Set<Equipment>) {
        if (value.isEmpty()) return
        viewModelScope.launch { equipmentSettings.set(value) }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setLocale(value: Locale) {
        locale.value = value
    }

    fun pick(exerciseId: String, onDone: () -> Unit) = add(ExerciseRef.ById(exerciseId), onDone)

    /** Picker only: adds an extended-catalogue exercise, creating its Room row the first time. */
    fun pickExtended(entry: ExtendedExercise, onDone: () -> Unit) = add(ExerciseRef.Extended(entry.id), onDone)

    private fun add(exercise: ExerciseRef, onDone: () -> Unit) {
        val command = addCommand(exercise) ?: return
        viewModelScope.launch {
            commands.execute(command)
            onDone()
        }
    }

    /** The command that adds [exercise] to the [target]; null in the tab. */
    private fun addCommand(exercise: ExerciseRef): Command? = when (val t = target) {
        is PickerTarget.Workout -> Command.AddExercise(t.workoutId, exercise)
        is PickerTarget.Routine -> Command.AddRoutineExercise(t.routineId, exercise)
        PickerTarget.Choose, null -> null
    }

    /** Creates a custom exercise; in picker mode it also goes straight into the workout or routine. */
    fun create(name: String, muscleGroup: MuscleGroup, equipment: Equipment, onDone: (addedToWorkout: Boolean) -> Unit) {
        viewModelScope.launch {
            val newId = commands.execute(Command.CreateExercise(name, muscleGroup, equipment)).id(CommandIds.EXERCISE_ID)
            newId?.let { addCommand(ExerciseRef.ById(it)) }?.let { commands.execute(it) }
            _query.value = ""
            onDone(target != null)
        }
    }
}
