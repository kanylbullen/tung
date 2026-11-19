package io.github.kanylbullen.tung.web.routines

import io.github.kanylbullen.tung.core.MuscleIndex
import io.github.kanylbullen.tung.core.RoutineSlot
import io.github.kanylbullen.tung.core.workoutMatchesRoutine
import io.github.kanylbullen.tung.core.workoutSlots
import io.github.kanylbullen.tung.core.muscles.ExerciseWork
import io.github.kanylbullen.tung.core.muscles.MuscleBreakdown
import io.github.kanylbullen.tung.core.muscles.muscleBreakdown
import io.github.kanylbullen.tung.core.sync.SyncExercise
import io.github.kanylbullen.tung.web.history.WorkoutDetail
import io.github.kanylbullen.tung.web.store.TungStore

/** One routine on the Routines tab: most recently used first, as on the phone. */
data class RoutineRow(val id: String, val name: String, val exerciseCount: Int, val lastUsedAt: Long?)

/** One exercise of a routine, in position order, with its targets. */
data class RoutineExerciseView(val id: String, val name: String, val targetSets: Int, val targetReps: Int?)

/** A routine's page. */
data class RoutineView(val id: String, val name: String, val exercises: List<RoutineExerciseView>)

/** Every routine, most recently used first, then by name. */
fun routineRows(store: TungStore): List<RoutineRow> {
    val counts = store.routineExercises.rows.values.map { it.row }.groupingBy { it.routineId }.eachCount()
    return store.routines.rows.values.map { it.row }
        .mapNotNull { r -> r.name?.let { RoutineRow(r.id, it, counts[r.id] ?: 0, r.lastUsedAt) } }
        .sortedWith(compareByDescending<RoutineRow> { it.lastUsedAt ?: 0L }.thenBy { it.name })
}

/** The routine [id] with its exercises in position order; null when it is gone. */
fun routineView(store: TungStore, id: String, displayName: (SyncExercise) -> String): RoutineView? {
    val routine = store.routines.get(id) ?: return null
    val exercises = store.routineExercises.rows.values.map { it.row }
        .filter { it.routineId == id }
        .sortedBy { it.position ?: 0 }
        .map { re ->
            RoutineExerciseView(
                id = re.id,
                name = re.exerciseId?.let { store.exercises.get(it) }?.let(displayName) ?: re.exerciseId ?: re.id,
                targetSets = re.targetSets ?: 0,
                targetReps = re.targetReps,
            )
        }
    return RoutineView(id, routine.name ?: "", exercises)
}

/** Whether a workout is in progress: a routine cannot be started then, as on the phone. */
fun workoutInProgress(store: TungStore): Boolean = store.workouts.rows.values.any { it.row.endedAt == null && it.row.startedAt != null }

/**
 * The muscles each routine works, the phone's `routineMuscles`: core's
 * [muscleBreakdown] over the catalogue with each exercise's target sets as
 * its sets, keyed by routine id. A custom exercise has no muscles.
 */
fun routineMuscles(store: TungStore, index: MuscleIndex): Map<String, MuscleBreakdown> =
    store.routineExercises.rows.values.map { it.row }
        .filter { it.routineId != null }
        .groupBy { it.routineId ?: "" }
        .mapValues { (_, rows) -> muscleBreakdown(rows.map { ExerciseWork(it.exerciseId?.let(index::musclesOf), it.targetSets ?: 0) }) }

/** What a finished workout offers to save as a routine (issue #217), the phone's `RoutineSaveChoice`. */
sealed interface RoutineSave {
    /** Not started from a routine, or it is gone: "Save as routine". */
    data object New : RoutineSave

    /** Did what its routine asks (same exercises, order and set counts): nothing to save. */
    data object Matches : RoutineSave

    /** Differs from its routine: "Update “Push day”" or "Save as new routine". */
    data class Differs(val routineId: String, val routineName: String) : RoutineSave
}

/** The choice for [detail] (its logged sets only) against the routine it was started from, as it is in [store] now. */
fun routineSave(store: TungStore, detail: WorkoutDetail): RoutineSave {
    val routineId = store.workouts.get(detail.id)?.routineId ?: return RoutineSave.New
    val routine = store.routines.get(routineId) ?: return RoutineSave.New
    val workout = workoutSlots(detail.exercises.map { (it.exerciseId ?: "") to it.sets.size })
    val target = store.routineExercises.rows.values.map { it.row }
        .filter { it.routineId == routineId }
        .sortedBy { it.position ?: 0 }
        .map { RoutineSlot(it.exerciseId ?: "", it.targetSets ?: 0) }
    return if (workoutMatchesRoutine(workout, target)) RoutineSave.Matches else RoutineSave.Differs(routineId, routine.name ?: "")
}
