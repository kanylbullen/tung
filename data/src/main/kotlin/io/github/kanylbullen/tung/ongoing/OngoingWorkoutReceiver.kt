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
 * The ongoing notification's **Log set**: runs [Command.LogSet] for the set
 * the notification showed, through the shared command handler, with no UI.
 * The notification then updates itself from the database. Does nothing when
 * that set is no longer the one to log (ticked meanwhile, deleted, or the
 * workout is over), so a stale notification can never tick the wrong set.
 */
class OngoingWorkoutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LOG_SET) return
        val workoutId = intent.getStringExtra(EXTRA_WORKOUT_ID) ?: return
        val setId = intent.getStringExtra(EXTRA_SET_ID) ?: return
        val container = (context.applicationContext as? TungHost)?.container ?: return
        val pending = goAsync()
        container.appScope.launch {
            try {
                val workouts = container.workoutRepository
                val set = workouts.getSet(setId)
                val active = workouts.observeActiveWorkout().first()
                if (active?.id == workoutId && set != null && !set.completed && set.reps > 0) {
                    container.commands.execute(Command.LogSet(workoutId, setId))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Log set from the notification failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_LOG_SET = "io.github.kanylbullen.tung.action.LOG_SET"
        const val EXTRA_WORKOUT_ID = "workoutId"
        const val EXTRA_SET_ID = "setId"
        private const val TAG = "OngoingWorkout"

        fun logSetIntent(context: Context, workoutId: String, setId: String): Intent =
            Intent(context, OngoingWorkoutReceiver::class.java)
                .setAction(ACTION_LOG_SET)
                .putExtra(EXTRA_WORKOUT_ID, workoutId)
                .putExtra(EXTRA_SET_ID, setId)
    }
}
