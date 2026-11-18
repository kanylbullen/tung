package io.github.kanylbullen.tung.ui.routines

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import io.github.kanylbullen.tung.core.RoutineSlot
import io.github.kanylbullen.tung.core.workoutMatchesRoutine
import io.github.kanylbullen.tung.core.workoutSlots
import io.github.kanylbullen.tung.data.Routine
import io.github.kanylbullen.tung.data.RoutineExerciseWithExercise
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.ui.common.WorkoutReport
import io.github.kanylbullen.tung.ui.resources.*
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.stringResource

/**
 * What a finished workout offers to save as a routine (issue #217), on the
 * summary and in the workout page's menu.
 */
sealed interface RoutineSaveChoice {
    /** Not known yet: offer nothing. */
    data object Loading : RoutineSaveChoice

    /** Not started from a routine, or that routine is gone: "Save as routine". */
    data object New : RoutineSaveChoice

    /** Did what its routine asks (core's [workoutMatchesRoutine]): nothing to save. */
    data class Matches(val routineName: String) : RoutineSaveChoice

    /** Differs from its routine: "Update “Push day”" (`update_routine_from_workout`) or "Save as new routine". */
    data class Differs(val routineId: String, val routineName: String) : RoutineSaveChoice
}

/** The routine a workout was started from, as Room has it now, with its exercises; null when there is none or it is gone. */
data class WorkoutRoutine(val routine: Routine, val exercises: List<RoutineExerciseWithExercise>)

/** [WorkoutRoutine] of the routine [routineId] names, following Room; null for no routine or one that is gone. */
@OptIn(ExperimentalCoroutinesApi::class)
fun RoutineRepository.observeWorkoutRoutine(routineId: Flow<String?>): Flow<WorkoutRoutine?> =
    routineId.distinctUntilChanged().flatMapLatest { id ->
        if (id == null) {
            flowOf(null)
        } else {
            combine(observeRoutine(id), observeRoutineExercises(id)) { routine, exercises -> routine?.let { WorkoutRoutine(it, exercises) } }
        }
    }

/**
 * The choice for [report]: [RoutineSaveChoice.New] without a routine,
 * else whether its logged exercises and set counts are the routine's.
 */
fun routineSaveChoice(report: WorkoutReport, routine: WorkoutRoutine?): RoutineSaveChoice {
    if (routine == null) return RoutineSaveChoice.New
    val workout = workoutSlots(report.exercises.map { it.exerciseId to it.sets.size })
    val target = routine.exercises.map { it.routineExercise }.sortedBy { it.position }.map { RoutineSlot(it.exerciseId, it.targetSets) }
    return if (workoutMatchesRoutine(workout, target)) {
        RoutineSaveChoice.Matches(routine.routine.name)
    } else {
        RoutineSaveChoice.Differs(routine.routine.id, routine.routine.name)
    }
}

/** "Update “Push day”?": what updating the routine from the workout does, before it does it. */
@Composable
fun UpdateRoutineDialog(routineName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tung.colors.raised,
        tonalElevation = 0.dp,
        shape = TungCorner,
        title = { Text(stringResource(Res.string.routine_update_title, routineName)) },
        text = { Text(stringResource(Res.string.routine_update_body), color = Tung.colors.muted) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.routine_update_confirm), color = Tung.colors.accentText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel), color = Tung.colors.text)
            }
        },
    )
}
