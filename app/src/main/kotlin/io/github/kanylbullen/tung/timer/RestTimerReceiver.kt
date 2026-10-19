package io.github.kanylbullen.tung.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives [RestAlarm]'s broadcast when a rest is over, whether or not the app is running. */
class RestTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RestAlarm.ACTION_REST_OVER) return
        val workoutId = intent.getStringExtra(RestAlarm.EXTRA_WORKOUT_ID) ?: return
        RestAlerts.restOver(context, workoutId, intent.getLongExtra(RestAlarm.EXTRA_ENDS_AT, 0L))
    }
}
