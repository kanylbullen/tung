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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
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
 * opens the workout when that set has no reps yet), or, with every set
 * ticked, **Add set** sends it the exercise to add a set to; **Finish** opens the
 * workout with the finish dialog ([TungHost.finishWorkoutIntent]); it never
 * finishes silently. [decorate] runs on the builder before it is posted: the
 * watch attaches its Ongoing Activity there.
 *
 * With [liveUpdate], on Android 16 (API 36) and above, the notification asks to
 * be a **Live Update**: promoted to the top of the shade and the lock screen,
 * with a chip in the status bar that shows the current set's numbers
 * ([chipText], "80×8"). The standard template it already uses is one of the
 * promotable styles, so title, body, chronometer and actions are the same;
 * the request is an extra the system honours only with
 * `POST_PROMOTED_NOTIFICATIONS` (granted by default, revocable in Settings)
 * and otherwise ignores, so a revoked permission leaves the ordinary ongoing
 * notification. The phone passes it; the watch never does (its chip is its
 * Ongoing Activity, and Wear OS 6 is Android 16 too, so the API level alone
 * would not keep it out). Below API 36 nothing is called, because the compat
 * builder writes the request and the chip text into the extras on every API
 * level. The chip follows the current set's weight and reps, so while it is
 * on the source is debounced by [DEBOUNCE_MILLIS] (as the watch publisher
 * does): a run of −/+ taps is one post, not one per tap, and the last one is
 * never the one the system's rate limit drops.
 *
 * Nothing is posted without the notification permission (Android 13+); the
 * rest timer's one-time request asks for it ([io.github.kanylbullen.tung.timer.RestTimerController.firstTimeAskingForNotifications]).
 */
class OngoingWorkoutNotifier(
    context: Context,
    private val source: Flow<OngoingWorkout?>,
    private val scope: CoroutineScope,
    private val actions: Boolean = true,
    private val liveUpdate: Boolean = false,
    private val decorate: (builder: NotificationCompat.Builder, workout: OngoingWorkout, open: PendingIntent) -> Unit = { _, _, _ -> },
) {
    private val context: Context = context.applicationContext ?: context
    private var job: Job? = null

    private val promoting: Boolean get() = liveUpdate && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA

    /** Starts following [source]; a second call while running does nothing. */
    @OptIn(FlowPreview::class)
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        val updates = if (promoting) source.debounce(DEBOUNCE_MILLIS) else source
        job = scope.launch { updates.collect(::show) }
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
            val addSetExerciseId = workout.addSetWorkoutExerciseId
            if (logSetId == null && addSetExerciseId != null) {
                val addSet = PendingIntent.getBroadcast(
                    context,
                    REQUEST_ADD_SET,
                    OngoingWorkoutReceiver.addSetIntent(context, workout.workoutId, addSetExerciseId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                builder.addAction(0, context.getString(R.string.ongoing_add_set), addSet)
            }
            builder.addAction(0, context.getString(R.string.ongoing_finish), activity(REQUEST_FINISH, host.finishWorkoutIntent(workout.workoutId)))
        }
        if (promoting) {
            // Android 16's Live Update (see the class comment). Guarded here, not left to the compat
            // builder, so that below 36 the notification carries neither extra.
            builder.setRequestPromotedOngoing(true)
            workout.chipText(context)?.let { builder.setShortCriticalText(it) }
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
        private const val REQUEST_ADD_SET = 23

        /** How long the Live Update waits for the next change before posting, like the watch publisher's. */
        const val DEBOUNCE_MILLIS = 100L

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
