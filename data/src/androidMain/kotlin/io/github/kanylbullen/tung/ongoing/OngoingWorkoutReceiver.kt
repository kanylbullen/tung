package io.github.kanylbullen.tung.ongoing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.data.TungHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The ongoing notification's **Log set** and **Add set**. Log set runs
 * [Command.LogSet] for the set the notification showed, through the shared
 * command handler, with no UI; Add set runs [Command.AddSet] for the exercise
 * it named, once every set is ticked. The notification then updates itself
 * from the database. Each does nothing when the notification is stale: Log
 * set when that set is no longer the one to log (ticked meanwhile, deleted,
 * or the workout is over), Add set when the workout is over, the exercise is
 * gone, or a set is open again (the notification would now offer Log set), so
 * a stale notification can never tick the wrong set or add one twice.
 */
class OngoingWorkoutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_LOG_SET && action != ACTION_ADD_SET) return
        val workoutId = intent.getStringExtra(EXTRA_WORKOUT_ID) ?: return
        val setId = intent.getStringExtra(EXTRA_SET_ID)
        val workoutExerciseId = intent.getStringExtra(EXTRA_WORKOUT_EXERCISE_ID)
        if (action == ACTION_LOG_SET && setId == null) return
        if (action == ACTION_ADD_SET && workoutExerciseId == null) return
        val container = (context.applicationContext as? TungHost)?.container ?: return
        val pending = goAsync()
        container.appScope.launch {
            try {
                val workouts = container.workoutRepository
                val active = workouts.observeActiveWorkout().first()
                if (active?.id == workoutId) {
                    if (setId != null && action == ACTION_LOG_SET) {
                        val set = workouts.getSet(setId)
                        if (set != null && !set.completed && set.reps > 0) {
                            container.commands.execute(Command.LogSet(workoutId, setId))
                        }
                    } else if (workoutExerciseId != null) {
                        val exercises = workouts.getExercisesWithSets(workoutId)
                        val allTicked = exercises.all { e -> e.sets.all { it.completed } }
                        if (allTicked && exercises.any { it.workoutExercise.id == workoutExerciseId }) {
                            container.commands.execute(Command.AddSet(workoutExerciseId))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$action from the notification failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_LOG_SET = "io.github.kanylbullen.tung.action.LOG_SET"
        const val ACTION_ADD_SET = "io.github.kanylbullen.tung.action.ADD_SET"
        const val EXTRA_WORKOUT_ID = "workoutId"
        const val EXTRA_SET_ID = "setId"
        const val EXTRA_WORKOUT_EXERCISE_ID = "workoutExerciseId"
        private const val TAG = "OngoingWorkout"

        fun logSetIntent(context: Context, workoutId: String, setId: String): Intent =
            Intent(context, OngoingWorkoutReceiver::class.java)
                .setAction(ACTION_LOG_SET)
                .putExtra(EXTRA_WORKOUT_ID, workoutId)
                .putExtra(EXTRA_SET_ID, setId)

        fun addSetIntent(context: Context, workoutId: String, workoutExerciseId: String): Intent =
            Intent(context, OngoingWorkoutReceiver::class.java)
                .setAction(ACTION_ADD_SET)
                .putExtra(EXTRA_WORKOUT_ID, workoutId)
                .putExtra(EXTRA_WORKOUT_EXERCISE_ID, workoutExerciseId)
    }
}
