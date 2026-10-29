package io.github.kanylbullen.tung.data

import io.github.kanylbullen.tung.core.MuscleIndex
import io.github.kanylbullen.tung.core.muscles.ExerciseWork
import io.github.kanylbullen.tung.core.muscles.MuscleBreakdown
import io.github.kanylbullen.tung.core.muscles.muscleBreakdown

/**
 * The muscles a routine works, by the catalogue ([index]) and weighted by each
 * exercise's target sets: core's [muscleBreakdown] as the summary uses it, with
 * the routine's planned sets in place of a workout's logged ones. A custom
 * exercise has no muscles and counts as uncounted.
 */
fun routineMuscles(exercises: List<Pair<String, Int>>, index: MuscleIndex): MuscleBreakdown =
    muscleBreakdown(exercises.map { (exerciseId, targetSets) -> ExerciseWork(index.musclesOf(exerciseId), targetSets) })

/** [routineMuscles] for every routine in [links], keyed by routine id. */
fun routineMuscles(links: List<RoutineExerciseLink>, index: MuscleIndex): Map<String, MuscleBreakdown> =
    links.groupBy { it.routineId }.mapValues { (_, rows) -> routineMuscles(rows.map { it.exerciseId to it.targetSets }, index) }
