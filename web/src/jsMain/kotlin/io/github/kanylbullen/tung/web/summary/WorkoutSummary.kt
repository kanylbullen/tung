package io.github.kanylbullen.tung.web.summary

import io.github.kanylbullen.tung.core.MuscleIndex
import io.github.kanylbullen.tung.core.isNewRecord
import io.github.kanylbullen.tung.core.muscles.ExerciseWork
import io.github.kanylbullen.tung.core.muscles.MuscleBreakdown
import io.github.kanylbullen.tung.core.muscles.muscleBreakdown
import io.github.kanylbullen.tung.core.sync.SyncExercise
import io.github.kanylbullen.tung.web.history.WorkoutDetail
import io.github.kanylbullen.tung.web.history.workoutDetail
import io.github.kanylbullen.tung.web.home.progressSets
import io.github.kanylbullen.tung.web.store.TungStore

/**
 * The screen after Finish, the phone's summary: the workout as its detail
 * shows it, which exercises set a record ([records], one per exercise of
 * [detail]), the muscles its logged sets worked, and the exercises to chart,
 * each once in workout order.
 */
data class WorkoutSummary(
    val detail: WorkoutDetail,
    val records: List<Boolean>,
    val muscles: MuscleBreakdown,
    val chartExerciseIds: List<String>,
)

/**
 * The summary of the finished workout [id], null when there is none (a
 * workout with nothing logged is deleted at Finish). An exercise is a
 * record when its heaviest logged set beats every earlier finished
 * workout's (core's `isNewRecord`: nothing to beat the first time); an
 * imported workout never is, as on the phone.
 */
fun workoutSummary(store: TungStore, id: String, muscles: MuscleIndex, displayName: (SyncExercise) -> String): WorkoutSummary? {
    val detail = workoutDetail(store, id, displayName) ?: return null
    val imported = store.workouts.get(id)?.importSource != null
    val heaviestBefore = progressSets(store)
        .filter { it.isLogged && it.startedAt < detail.startedAt }
        .groupBy { it.exerciseId }
        .mapValues { (_, sets) -> sets.maxOf { it.set.weightKg } }
    return WorkoutSummary(
        detail = detail,
        records = detail.exercises.map { exercise ->
            !imported && exercise.exerciseId != null && isNewRecord(exercise.sets, heaviestBefore[exercise.exerciseId])
        },
        muscles = muscleBreakdown(detail.exercises.map { ExerciseWork(it.exerciseId?.let(muscles::musclesOf), it.sets.size) }),
        chartExerciseIds = detail.exercises.mapNotNull { it.exerciseId }.distinct(),
    )
}
