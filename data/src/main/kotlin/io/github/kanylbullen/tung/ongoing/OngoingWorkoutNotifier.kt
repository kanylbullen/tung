package io.github.kanylbullen.tung.ongoing

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.kanylbullen.tung.data.R
import io.github.kanylbullen.tung.data.TungHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The ongoing notification of a workout in progress, whoever started it (the
 * phone, the watch, an assistant through the server and a sync): "Workout in
 * progress · Bench press · set 3 of 5" with the elapsed time, shown while
 * [source] has a workout and gone when it emits null (finished or discarded).
 * Not a foreground service: an ongoing notification on a low-importance
 * channel, re-posted silently on every change.
 *
 * Tapping it opens the workout ([TungHost.openWorkoutIntent]). With
 * [actions], **Log set** sends [OngoingWorkoutReceiver] the current set (or
 * opens the workout when that set has no reps yet) and **Finish** opens the
 * workout with the finish dialog ([TungHost.finishWorkoutIntent]); it never
 * finishes silently. [decorate] runs on the builder before it is posted: the
 * watch attaches its Ongoing Activity there.
 *
 * Nothing is posted without the notification permission (Android 13+); the
 * rest timer's one-time request asks for it ([io.github.kanylbullen.tung.timer.RestTimerController.firstTimeAskingForNotifications]).
 */
class OngoingWorkoutNotifier(
    context: Context,
    private val source: Flow<OngoingWorkout?>,
    private val scope: CoroutineScope,
    private val actions: Boolean = true,
    private val decorate: (builder: NotificationCompat.Builder, workout: OngoingWorkout, open: PendingIntent) -> Unit = { _, _, _ -> },
) {
    private val context: Context = context.applicationContext ?: context
    private var job: Job? = null

    /** Starts following [source]; a second call while running does nothing. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { source.collect(::show) }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        cancel(context)
    }

    private fun show(workout: OngoingWorkout?) {
        if (workout == null) {
            cancel(context)
            return
        }
        val host = context as? TungHost ?: return
        val manager = NotificationManagerCompat.from(context)
        if (!canPost(context) || !manager.areNotificationsEnabled()) return
        ensureChannel(context)
        val open = activity(REQUEST_OPEN, host.openWorkoutIntent(workout.workoutId))
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(host.notificationIcon)
            .setContentTitle(context.getString(R.string.ongoing_title))
            .setContentText(workout.status.text(context))
            .setSubText(workout.startedByText(context))
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(true)
            .setWhen(workout.startedAt)
            .setUsesChronometer(true)
            .setContentIntent(open)
        if (actions) {
            val logSetId = workout.logSetId
            if (logSetId != null) {
                val logSet = if (workout.logSetHasReps) {
                    PendingIntent.getBroadcast(
                        context,
                        REQUEST_LOG_SET,
                        OngoingWorkoutReceiver.logSetIntent(context, workout.workoutId, logSetId),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                } else {
                    open
                }
                builder.addAction(0, context.getString(R.string.ongoing_log_set), logSet)
            }
            builder.addAction(0, context.getString(R.string.ongoing_finish), activity(REQUEST_FINISH, host.finishWorkoutIntent(workout.workoutId)))
        }
        decorate(builder, workout, open)
        try {
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // The permission was withdrawn between the check and the call.
        }
    }

    private fun activity(requestCode: Int, intent: Intent): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL_ID = "workout_in_progress"

        /** The rest timer's "Rest over" is 1. */
        const val NOTIFICATION_ID = 2

        private const val REQUEST_OPEN = 20
        private const val REQUEST_LOG_SET = 21
        private const val REQUEST_FINISH = 22

        fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

        /** Whether this app may post notifications at all (the runtime permission on Android 13+). */
        fun canPost(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.ongoing_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.ongoing_channel_description)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
