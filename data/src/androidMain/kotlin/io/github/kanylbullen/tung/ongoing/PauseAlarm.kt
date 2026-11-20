package io.github.kanylbullen.tung.ongoing

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Wakes the app when the workout in progress pauses (an hour without input,
 * `core.pausedSince`, issue #261), so the ongoing notification stops counting
 * even when the process is gone by then: the broadcast reaches
 * [OngoingWorkoutReceiver], which re-posts the notification
 * ([io.github.kanylbullen.tung.data.TungHost.refreshOngoingWorkout]). Inexact
 * on purpose (`setAndAllowWhileIdle`): a few minutes late is fine, and no
 * exact-alarm permission is asked for. While the process runs the notifier
 * re-posts on time by itself.
 */
object PauseAlarm {

    fun schedule(context: Context, atMillis: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent(context))
    }

    fun cancel(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val operation = pendingIntent(context)
        alarms.cancel(operation)
        operation.cancel()
    }

    /** Sets the alarm for [atMillis], or cancels it for null: what [OngoingWorkoutNotifier]'s `pauseAlarm` takes. */
    fun set(context: Context, atMillis: Long?) = if (atMillis == null) cancel(context) else schedule(context, atMillis)

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, OngoingWorkoutReceiver::class.java).setAction(OngoingWorkoutReceiver.ACTION_REFRESH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private const val REQUEST_CODE = 24
}
