package io.github.kanylbullen.tung.timer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.kanylbullen.tung.MainActivity
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.TungApp
import java.util.concurrent.atomic.AtomicLong

/**
 * What happens when a rest is over: a vibration always, and a "Rest over"
 * notification when the app is not on screen and notifications are allowed.
 * Tapping the notification opens the active workout.
 *
 * Both the alarm ([RestTimerReceiver]) and the countdown on screen can get
 * here for the same rest; whichever comes first alerts and the other is a no-op.
 */
object RestAlerts {

    const val CHANNEL_ID = "rest_timer"
    const val NOTIFICATION_ID = 1

    /** Duration pattern: three short pulses, strong enough to feel through a pocket. */
    private val PATTERN = longArrayOf(0, 350, 150, 350, 150, 350)

    private val lastAlerted = AtomicLong(Long.MIN_VALUE)

    fun restOver(context: Context, workoutId: String, endsAtMillis: Long) {
        if (lastAlerted.getAndSet(endsAtMillis) == endsAtMillis) return
        vibrate(context)
        val visible = (context.applicationContext as? TungApp)?.isInForeground == true
        if (!visible) notify(context, workoutId)
    }

    fun cancelNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun vibrate(context: Context) {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        if (vibrator?.hasVibrator() != true) return
        vibrator.vibrate(VibrationEffect.createWaveform(PATTERN, -1))
    }

    private fun notify(context: Context, workoutId: String) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val manager = NotificationManagerCompat.from(context)
        if (!allowed || !manager.areNotificationsEnabled()) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_WORKOUT, workoutId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dumbbell)
            .setContentTitle(context.getString(R.string.rest_over))
            .setContentText(context.getString(R.string.rest_over_text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission withdrawn between the check and the call; the vibration already went off.
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.rest_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.rest_channel_description)
            // The alert vibrates itself, also when no notification is posted; the channel must not add a second buzz.
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }
}
