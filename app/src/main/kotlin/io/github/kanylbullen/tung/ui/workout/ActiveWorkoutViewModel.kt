package io.github.kanylbullen.tung.ui.workout

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.core.DEFAULT_REST_SECONDS
import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.RestTimer
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandHandler
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.id
import io.github.kanylbullen.tung.core.nextCurrentAfterLogging
import io.github.kanylbullen.tung.core.restSecondsFor
import io.github.kanylbullen.tung.data.WorkoutExerciseWithSets
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.timer.RestTimerController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveWorkoutViewModel(
    private val workoutId: String,
    /** Reads only: the workout and its sets as Flows, and last session's sets. */
    private val repo: WorkoutRepository,
    /** Every change to the workout goes through a command. */
    private val commands: CommandHandler,
    private val restTimer: RestTimerController,
    private val savedState: SavedStateHandle,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    /**
     * Last session's sets per exercise. History cannot change while a workout
     * is in progress, so each exercise is looked up once.
     */
    private val previousByExercise = mutableMapOf<String, List<LoggedSet>>()
    private val finishing = MutableStateFlow(false)

    /**
     * The rest countdown. Kept in [savedState] (process death with the screen
     * restored) and, through [restTimer], in DataStore and an alarm (process
     * death without it, and the alert in the background). Only timestamps are
     * stored; what is left is computed from the clock.
     */
    private val rest = MutableStateFlow(restoredRest()?.takeIf { it.isRunningAt(clock()) })
    private val lastTouched = MutableStateFlow(savedState.get<String>(KEY_TOUCHED))

    /** The set the user made current by tapping a header or a set number; kept like [lastTouched]. */
    private val selected = MutableStateFlow(savedState.get<String>(KEY_SELECTED))
    private val scroll = MutableStateFlow<ScrollRequest?>(null)
    private var scrollSeq = 0L
    private val defaultRest = restTimer.defaultSeconds
        .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_REST_SECONDS)

    /** Workout exercises seen so far; one that appears later was just added, and becomes the touched one. */
    private var knownExercises: Set<String>? = null

    private val exercisesWithPrevious: Flow<Pair<List<WorkoutExerciseWithSets>, Map<String, List<LoggedSet>>>> =
        repo.observeExercises(workoutId).onEach(::noteAddedExercise).mapLatest { list ->
            list.map { it.exercise.id }.distinct()
                .filterNot { it in previousByExercise }
                .forEach { id -> previousByExercise[id] = repo.previousSessionSets(id, workoutId) }
            list to previousByExercise.toMap()
        }

    private val workoutState: Flow<ActiveWorkoutUiState> = combine(
        repo.observeWorkout(workoutId),
        exercisesWithPrevious,
        finishing,
        defaultRest,
    ) { workout, (exercises, previous), isFinishing, defaultSeconds ->
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
                        restSeconds = restSecondsFor(e.exercise.restSeconds, defaultSeconds),
                    )
                },
            )
        }
    }

    val state: StateFlow<ActiveWorkoutUiState> = combine(
        workoutState,
        rest,
        lastTouched,
        selected,
        scroll,
    ) { s, timer, touched, selection, scrollRequest ->
        s.copy(rest = timer, lastTouchedExerciseId = touched, selectedSetId = selection, scrollRequest = scrollRequest)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActiveWorkoutUiState())

    private val eventChannel = Channel<ActiveWorkoutEvent>(Channel.BUFFERED)
    val events: Flow<ActiveWorkoutEvent> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            // Every change after the first value goes to DataStore and the alarm, in order.
            rest.drop(1).collect { restTimer.set(workoutId, it) }
        }
        viewModelScope.launch {
            // No saved state (the app was started anew): fall back to what DataStore kept.
            if (rest.value == null) {
                restTimer.stored(workoutId)?.takeIf { it.isRunningAt(clock()) }?.let(::setRest)
            }
        }
        viewModelScope.launch {
            // Asked once, the first time a workout is on screen: both the ongoing notification
            // ("Workout in progress") and the rest timer's "Rest over" need it.
            if (restTimer.firstTimeAskingForNotifications()) eventChannel.send(ActiveWorkoutEvent.AskForNotifications)
        }
    }

    fun addSet(workoutExerciseId: String) {
        touch(workoutExerciseId)
        launch {
            commands.execute(Command.AddSet(workoutExerciseId)).id(CommandIds.SET_ID)
                ?.let { requestScroll(ScrollRequest(setId = it, mode = ScrollMode.IntoView, seq = 0)) }
        }
    }

    /** A tap on an exercise's header: its first un-ticked set becomes the current set. */
    fun selectExercise(workoutExerciseId: String) {
        val exercise = state.value.exercises.firstOrNull { it.workoutExerciseId == workoutExerciseId } ?: return
        val set = exercise.sets.firstOrNull { !it.completed } ?: return
        select(workoutExerciseId, set.id)
    }

    /** A tap on the number of an un-ticked set: that set becomes the current set. */
    fun selectSet(workoutExerciseId: String, setId: String) {
        val set = state.value.findSet(setId) ?: return
        if (set.completed) return
        select(workoutExerciseId, setId)
    }

    /** The screen has carried out [request]. */
    fun onScrollHandled(request: ScrollRequest) {
        scroll.compareAndSet(request, null)
    }

    fun setWeight(workoutExerciseId: String, setId: String, weightKg: Double) {
        touch(workoutExerciseId)
        launch { commands.execute(Command.SetWeight(setId, weightKg)) }
    }

    fun setReps(workoutExerciseId: String, setId: String, reps: Int) {
        touch(workoutExerciseId)
        launch { commands.execute(Command.SetReps(setId, reps)) }
    }

    /** "Apply to all sets": [setId] and every un-ticked set of the exercise get the weight. */
    fun setWeightForOpenSets(workoutExerciseId: String, setId: String, weightKg: Double) {
        touch(workoutExerciseId)
        launch { commands.execute(Command.SetWeight(setId, weightKg, applyToOpenSets = true)) }
    }

    /** "Apply to all sets": [setId] and every un-ticked set of the exercise get the reps. */
    fun setRepsForOpenSets(workoutExerciseId: String, setId: String, reps: Int) {
        touch(workoutExerciseId)
        launch { commands.execute(Command.SetReps(setId, reps, applyToOpenSets = true)) }
    }

    /** The tick on the row. Ticking (not un-ticking) starts the rest timer too. */
    fun setCompleted(exercise: ActiveExerciseUi, setId: String, completed: Boolean) {
        if (completed) {
            advanceAfterLogging(state.value, setId, addedSetId = null)
        } else {
            touch(exercise.workoutExerciseId)
        }
        launch { commands.execute(Command.TickSet(setId, completed)) }
        if (completed) startRest(exercise)
    }

    /**
     * "Log set": ticks [set], adds the next set when [set] was the exercise's
     * last ([Command.LogSet], on the set this screen shows as current) and
     * starts the rest timer.
     */
    fun logSet(exercise: ActiveExerciseUi, set: ActiveSetUi) {
        val before = state.value
        launch {
            val added = commands.execute(Command.LogSet(workoutId, set.id)).id(CommandIds.ADDED_SET_ID)
            advanceAfterLogging(before, set.id, added, scrollAlways = true)
        }
        startRest(exercise)
    }

    fun skipRest() = setRest(rest.value?.skip())

    fun addRest() {
        rest.value?.let { setRest(it.plus(clock())) }
    }

    /** The countdown on screen reached zero: alert (unless the alarm already did) and clear it. */
    fun onRestOver(timer: RestTimer) {
        if (rest.value != timer) return
        restTimer.restOver(workoutId, timer.endsAtMillis)
        setRest(null)
    }

    /** The rest timer of an exercise, in seconds; 0 turns it off. */
    fun setRestSeconds(exerciseId: String, seconds: Int) = launch { commands.execute(Command.SetRest(exerciseId, seconds)) }

    fun deleteSet(setId: String) = launch { commands.execute(Command.RemoveSet(setId)) }

    fun removeExercise(workoutExerciseId: String) = launch { commands.execute(Command.RemoveExercise(workoutExerciseId)) }

    fun finish() = launch {
        finishing.value = true
        setRest(null)
        // Directly as well: the screen leaves right after, and the collector goes with it.
        restTimer.set(workoutId, null)
        // FinishWorkout returns the workout's id only when it was kept.
        val saved = commands.execute(Command.FinishWorkout(workoutId)).id(CommandIds.WORKOUT_ID) != null
        eventChannel.send(ActiveWorkoutEvent.Finished(workoutId, saved))
    }

    private fun startRest(exercise: ActiveExerciseUi) {
        val timer = RestTimer.start(clock(), exercise.restSeconds)
        setRest(timer)
    }

    private fun setRest(timer: RestTimer?) {
        rest.value = timer
        savedState[KEY_ENDS_AT] = timer?.endsAtMillis
        savedState[KEY_DURATION] = timer?.durationMillis
    }

    private fun restoredRest(): RestTimer? {
        val endsAt = savedState.get<Long>(KEY_ENDS_AT) ?: return null
        return RestTimer(endsAt, savedState.get<Long>(KEY_DURATION) ?: 0L)
    }

    private fun touch(workoutExerciseId: String) {
        lastTouched.value = workoutExerciseId
        savedState[KEY_TOUCHED] = workoutExerciseId
    }

    private fun select(workoutExerciseId: String, setId: String?) {
        touch(workoutExerciseId)
        selected.value = setId
        savedState[KEY_SELECTED] = setId
    }

    /**
     * Moves the current set on after the set [loggedId] was ticked, by the
     * rule in [nextCurrentAfterLogging], working on [before] (the state as it
     * was when the set was ticked) with the set ticked and [addedSetId], the
     * set "Log set" added after it, appended. The move clears a manual
     * selection: the next set is always the first open one of its exercise,
     * which is what the touched exercise gives. When the current set lands in
     * another exercise, or [scrollAlways], it is scrolled into view.
     */
    private fun advanceAfterLogging(before: ActiveWorkoutUiState, loggedId: String, addedSetId: String?, scrollAlways: Boolean = false) {
        val logged = before.indexOfSet(loggedId) ?: return
        val completed = before.completedMatrix().mapIndexed { e, sets ->
            if (e != logged.exercise) sets
            else sets.mapIndexed { i, done -> done || i == logged.set } + listOfNotNull(addedSetId?.let { false })
        }
        val next = nextCurrentAfterLogging(completed, logged)
        val exercise = before.exercises[next?.exercise ?: logged.exercise]
        select(exercise.workoutExerciseId, null)
        if (next == null) return
        val nextSetId = exercise.sets.getOrNull(next.set)?.id ?: addedSetId ?: return
        if (scrollAlways || next.exercise != logged.exercise) {
            requestScroll(ScrollRequest(setId = nextSetId, mode = ScrollMode.IntoView, seq = 0))
        }
    }

    private fun requestScroll(request: ScrollRequest) {
        scroll.value = request.copy(seq = ++scrollSeq)
    }

    private fun noteAddedExercise(list: List<WorkoutExerciseWithSets>) {
        val ids = list.map { it.workoutExercise.id }
        val known = knownExercises
        knownExercises = ids.toSet()
        if (known != null) {
            ids.lastOrNull { it !in known }?.let { added ->
                // Just added, so it is what the user means to do next: it becomes current, over a manual choice.
                select(added, null)
                requestScroll(ScrollRequest(workoutExerciseId = added, mode = ScrollMode.ToTop, seq = 0))
            }
        }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        const val KEY_ENDS_AT = "rest_ends_at"
        const val KEY_DURATION = "rest_duration"
        const val KEY_TOUCHED = "last_touched_exercise"
        const val KEY_SELECTED = "selected_set"
    }
}
