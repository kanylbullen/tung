package io.github.kanylbullen.tung.wear

import android.content.Context
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import io.github.kanylbullen.tung.core.OngoingExercise
import io.github.kanylbullen.tung.core.OngoingSet
import io.github.kanylbullen.tung.core.SetIndex
import io.github.kanylbullen.tung.core.ongoingStatus
import io.github.kanylbullen.tung.core.wear.WatchWorkoutState
import io.github.kanylbullen.tung.data.TungContainer
import io.github.kanylbullen.tung.ongoing.OngoingWorkout
import io.github.kanylbullen.tung.ongoing.OngoingWorkoutNotifier
import io.github.kanylbullen.tung.ongoing.text
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The workout in progress in a [WatchWorkoutState] as the ongoing
 * notification sees it, or null when there is none. Pure: the same state
 * gives the same status in both modes. The current set is the state's own
 * ([WatchWorkoutState.currentSetId]: the watch's selection standalone, the
 * phone's rule in companion mode), counted against the routine's target
 * sets the state carries; with every set ticked, "Bench press · all sets
 * logged" for the exercise the state offers Add set on.
 */
fun watchOngoing(state: WatchWorkoutState?): OngoingWorkout? {
    val workoutId = state?.workoutId ?: return null
    val exercises = state.exercises.map { e ->
        OngoingExercise(e.name, e.sets.map { OngoingSet(it.completed, it.reps) }, e.targetSets)
    }
    val current = state.currentSetId?.let { id ->
        state.exercises.withIndex().firstNotNullOfOrNull { (ei, e) ->
            e.sets.indexOfFirst { it.setId == id }.takeIf { it >= 0 }?.let { SetIndex(ei, it) }
        }
    }
    return OngoingWorkout(
        workoutId = workoutId,
        startedAt = state.startedAt ?: state.updatedAt,
        status = ongoingStatus(exercises, current, state.addSetExerciseIndex()),
        startedBy = state.startedBy,
    )
}

/**
 * The watch's Ongoing Activity (the chip on the watch face and in recents):
 * data's ongoing notification ([OngoingWorkoutNotifier]) with an
 * [OngoingActivity] attached, over whatever state the watch shows, in both
 * modes: the watch's own database standalone ([LocalWatchBackend]), the
 * phone's Data Layer state in companion mode ([PhoneLink], while the watch
 * app's process runs). No Log set or Finish actions here: in companion mode
 * the watch's own database is not the workout, so the actions would act on
 * the wrong one; tapping the chip opens the watch's workout screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchOngoingActivity(
    private val context: Context,
    container: TungContainer,
    local: () -> WatchBackend,
    phone: () -> WatchBackend,
) {
    private val source: Flow<OngoingWorkout?> =
        container.syncAccount.state.map { it.signedIn }.filterNotNull().map(::watchMode).distinctUntilChanged()
            .flatMapLatest { mode ->
                when (mode) {
                    WatchMode.STANDALONE -> local().states()
                    // Without Play services there is no Data Layer, and no phone to mirror.
                    WatchMode.COMPANION -> if (playServicesAvailable(context)) phone().states() else flowOf(null)
                }
            }
            .map(::watchOngoing)
            .distinctUntilChanged()

    private val notifier = OngoingWorkoutNotifier(context, source, container.appScope, actions = false) { builder, workout, open ->
        OngoingActivity.Builder(context, OngoingWorkoutNotifier.NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_dumbbell)
            .setTouchIntent(open)
            // A part rather than the text as the template: '#' in a custom exercise name would read as a placeholder.
            .setStatus(Status.Builder().addTemplate("#status#").addPart("status", Status.TextPart(workout.status.text(context))).build())
            .build()
            .apply(context)
    }

    fun start() = notifier.start()

    private fun playServicesAvailable(context: Context): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }.getOrDefault(false)
}
