package io.github.kanylbullen.tung.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * The one-shot alarm that ends a rest with the app in the background or the
 * screen off. No foreground service: a single alarm and a broadcast are enough.
 *
 * Exact when the system allows it: always below Android 12, and on 12+ when
 * the user has granted "Alarms & reminders" (`SCHEDULE_EXACT_ALARM`; denied by
 * default for new installs on Android 14+). Otherwise
 * `setAndAllowWhileIdle`, which may fire somewhat late; with the workout on
 * screen the countdown ends the rest on time either way. `USE_EXACT_ALARM` is
 * not used: Play reserves it for apps whose core function is an alarm clock,
 * timer or calendar, and a training log is not one.
 */
internal object RestAlarm {

    const val ACTION_REST_OVER = "io.github.kanylbullen.tung.action.REST_OVER"
    const val EXTRA_WORKOUT_ID = "workoutId"
    const val EXTRA_ENDS_AT = "endsAt"

    fun schedule(context: Context, workoutId: String, endsAtMillis: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val operation = pendingIntent(context, workoutId, endsAtMillis)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        try {
            if (exact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAtMillis, operation)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAtMillis, operation)
            }
        } catch (_: SecurityException) {
            // The exact-alarm grant can be revoked between the check and the call.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAtMillis, operation)
        }
    }

    fun cancel(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        // Extras do not take part in matching, so this finds the scheduled one.
        val operation = pendingIntent(context, workoutId = null, endsAtMillis = 0L)
        alarms.cancel(operation)
        operation.cancel()
    }

    private fun pendingIntent(context: Context, workoutId: String?, endsAtMillis: Long): PendingIntent {
        val intent = Intent(context, RestTimerReceiver::class.java)
            .setAction(ACTION_REST_OVER)
            .putExtra(EXTRA_WORKOUT_ID, workoutId)
            .putExtra(EXTRA_ENDS_AT, endsAtMillis)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
