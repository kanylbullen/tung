package io.github.kanylbullen.tung.timer

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.kanylbullen.tung.core.RestTimer
import kotlinx.coroutines.flow.Flow

/**
 * The rest timer's Android side: where it is kept between processes
 * ([StoredRestTimer] over [settings], a Preferences DataStore), the alarm that
 * ends it ([RestAlarm]) and what happens when it ends ([RestAlerts]). The
 * countdown itself is [RestTimer] in `core`; the active workout's ViewModel
 * owns the current value.
 */
class RestTimerController(
    private val context: Context,
    settings: DataStore<Preferences>,
) : RestTimerStore {
    private val stored = StoredRestTimer(settings)

    override val defaultSeconds: Flow<Int> = stored.defaultSeconds

    override suspend fun setDefaultSeconds(seconds: Int) = stored.setDefaultSeconds(seconds)

    override suspend fun stored(workoutId: String): RestTimer? = stored.stored(workoutId)

    /**
     * Makes [timer] the rest timer of [workoutId]: sets (or, for null, cancels)
     * the alarm that ends it and stores it. A new or cleared timer also takes
     * away a "Rest over" notification still showing from the last one.
     */
    override suspend fun set(workoutId: String, timer: RestTimer?) {
        RestAlerts.cancelNotification(context)
        if (timer == null) RestAlarm.cancel(context) else RestAlarm.schedule(context, workoutId, timer.endsAtMillis)
        stored.save(workoutId, timer)
    }

    /**
     * The ongoing notification ([io.github.kanylbullen.tung.ongoing.OngoingWorkoutNotifier])
     * and "Rest over" share the one permission and the one question.
     */
    override suspend fun firstTimeAskingForNotifications(): Boolean = stored.firstTimeAskingForNotifications()

    /** The rest is over while the workout is on screen: the same alert as the alarm's. */
    override fun restOver(workoutId: String, endsAtMillis: Long) = RestAlerts.restOver(context, workoutId, endsAtMillis)
}
