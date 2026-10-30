package io.github.kanylbullen.tung.web.home

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.progress.ProgressSet
import io.github.kanylbullen.tung.core.progress.ThisWeek
import io.github.kanylbullen.tung.core.progress.thisWeek
import io.github.kanylbullen.tung.web.history.HistoryRow
import io.github.kanylbullen.tung.web.history.historyRows
import io.github.kanylbullen.tung.web.store.TungStore
import io.github.kanylbullen.tung.core.sync.SyncExercise
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** How many routines and recent workouts Home lists, as the phone's Home. */
const val HOME_ROUTINES = 3
const val HOME_RECENT = 3

/** One routine as Home lists it: most recently used first. */
data class HomeRoutine(val id: String, val name: String, val exerciseCount: Int, val lastUsedAt: Long?)

/** The workout in progress, wherever it was started (this browser, the phone, the watch, an assistant). */
data class InProgress(val id: String, val startedAt: Long, val routineName: String?, val startedBy: String?)

/** What Home shows; [thisWeek] is null until there is a finished workout at all. */
data class HomeModel(
    val thisWeek: ThisWeek?,
    val inProgress: InProgress?,
    val routines: List<HomeRoutine>,
    val recent: List<HistoryRow>,
)

/**
 * Every set of a finished workout as core's progress functions take it
 * (the phone's `WorkoutDao.observeProgressSets`); core drops the ones that
 * do not count.
 */
fun progressSets(store: TungStore): List<ProgressSet> {
    val finished = store.workouts.rows.values.map { it.row }.filter { it.endedAt != null && it.startedAt != null }.associateBy { it.id }
    val exercises = store.workoutExercises.rows.values.map { it.row }.filter { it.workoutId in finished }.associateBy { it.id }
    return store.sets.rows.values.mapNotNull { stored ->
        val set = stored.row
        val exercise = exercises[set.workoutExerciseId] ?: return@mapNotNull null
        val workout = finished[exercise.workoutId] ?: return@mapNotNull null
        ProgressSet(
            exerciseId = exercise.exerciseId ?: return@mapNotNull null,
            workoutId = workout.id,
            workoutExerciseId = exercise.id,
            startedAt = workout.startedAt ?: return@mapNotNull null,
            exercisePosition = exercise.position ?: 0,
            setPosition = set.position ?: 0,
            set = LoggedSet(set.weightKg ?: 0.0, set.reps ?: 0),
            completed = set.completed ?: false,
        )
    }
}

/** Home's model from the store: [today] and [zone] are the browser's. */
fun homeModel(store: TungStore, today: LocalDate, zone: TimeZone, displayName: (SyncExercise) -> String): HomeModel {
    val recent = historyRows(store, displayName)
    val active = store.workouts.rows.values.map { it.row }.filter { it.endedAt == null && it.startedAt != null }.maxByOrNull { it.startedAt ?: 0L }
    val exerciseCounts = store.routineExercises.rows.values.map { it.row }.groupingBy { it.routineId }.eachCount()
    val routines = store.routines.rows.values.map { it.row }
        .mapNotNull { routine -> routine.name?.let { HomeRoutine(routine.id, it, exerciseCounts[routine.id] ?: 0, routine.lastUsedAt) } }
        .sortedWith(compareByDescending<HomeRoutine> { it.lastUsedAt ?: 0L }.thenBy { it.name })
        .take(HOME_ROUTINES)
    return HomeModel(
        thisWeek = if (recent.isEmpty()) null else thisWeek(progressSets(store), today, zone),
        inProgress = active?.let { workout ->
            InProgress(workout.id, workout.startedAt ?: 0L, workout.routineId?.let { store.routines.get(it)?.name }, workout.startedBy)
        },
        routines = routines,
        recent = recent.take(HOME_RECENT),
    )
}
