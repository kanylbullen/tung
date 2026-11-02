package io.github.kanylbullen.tung.timer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.kanylbullen.tung.core.RestTimer
import kotlinx.coroutines.flow.Flow
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.AudioToolbox.kSystemSoundID_Vibrate
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter

/**
 * The rest timer on the iPhone: kept in the `settings` DataStore like the
 * phone's ([StoredRestTimer]), and ended by a local notification at
 * `endsAt` (iOS delivers it when the app is not in front; there is no alarm
 * to schedule). With the workout on screen [restOver] vibrates instead.
 * [title] and [body] are the notification's text in the app's language.
 */
class IosRestTimer(
    settings: DataStore<Preferences>,
    private val title: suspend () -> String,
    private val body: suspend () -> String,
) : RestTimerStore {
    private val stored = StoredRestTimer(settings)
    private val center get() = UNUserNotificationCenter.currentNotificationCenter()

    override val defaultSeconds: Flow<Int> = stored.defaultSeconds

    override suspend fun setDefaultSeconds(seconds: Int) = stored.setDefaultSeconds(seconds)

    override suspend fun stored(workoutId: String): RestTimer? = stored.stored(workoutId)

    /** Replaces the pending (and any delivered) "Rest over" with [timer]'s, or none for null, and stores it. */
    override suspend fun set(workoutId: String, timer: RestTimer?) {
        center.removePendingNotificationRequestsWithIdentifiers(listOf(REQUEST_ID))
        center.removeDeliveredNotificationsWithIdentifiers(listOf(REQUEST_ID))
        if (timer != null) {
            val seconds = (timer.endsAtMillis / 1000.0) - NSDate().timeIntervalSince1970
            if (seconds > 0) {
                val title = title()
                val body = body()
                val content = UNMutableNotificationContent().apply {
                    setTitle(title)
                    setBody(body)
                    setSound(UNNotificationSound.defaultSound)
                    setUserInfo(mapOf(WORKOUT_ID to workoutId))
                }
                val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(seconds, repeats = false)
                center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(REQUEST_ID, content, trigger), null)
            }
        }
        stored.save(workoutId, timer)
    }

    override suspend fun firstTimeAskingForNotifications(): Boolean = stored.firstTimeAskingForNotifications()

    /** The rest is over while the workout is on screen: a vibration, as the phone's alert gives. */
    override fun restOver(workoutId: String, endsAtMillis: Long) {
        AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
    }

    private companion object {
        const val REQUEST_ID = "rest_over"
        const val WORKOUT_ID = "workout_id"
    }
}
