package io.github.kanylbullen.tung.timer

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import io.github.kanylbullen.tung.MainActivity
import io.github.kanylbullen.tung.data.R
import io.github.kanylbullen.tung.TungApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The end of a rest with the app in the background: the alarm's broadcast
 * reaches [RestTimerReceiver], which vibrates and posts "Rest over" when
 * notifications are allowed. No activity is started here, so the app counts as
 * not on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class RestAlertsTest {

    private val app: TungApp = ApplicationProvider.getApplicationContext()
    private val notifications get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private val vibrator get() = shadowOf(app.getSystemService(VibratorManager::class.java).defaultVibrator)

    private fun restOver(workoutId: String, endsAt: Long) = RestTimerReceiver().onReceive(
        app,
        Intent(RestAlarm.ACTION_REST_OVER)
            .putExtra(RestAlarm.EXTRA_WORKOUT_ID, workoutId)
            .putExtra(RestAlarm.EXTRA_ENDS_AT, endsAt),
    )

    @Test
    fun theAlarmVibratesAndPostsRestOverThatOpensTheWorkout() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        restOver("w-1", endsAt = 1_000L)

        assertTrue(vibrator.isVibrating)
        assertTrue(vibrator.pattern.isNotEmpty())
        val posted = notifications.allNotifications
        assertEquals(1, posted.size)
        val notification = posted.single()
        assertEquals(app.getString(R.string.rest_over), notification.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertEquals(RestAlerts.CHANNEL_ID, notification.channelId)
        assertNotNull(app.getSystemService(NotificationManager::class.java).getNotificationChannel(RestAlerts.CHANNEL_ID))

        val open = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, open.component?.className)
        assertEquals("w-1", open.getStringExtra(MainActivity.EXTRA_OPEN_WORKOUT))
    }

    @Test
    fun withoutTheNotificationPermissionItStillVibrates() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        restOver("w-2", endsAt = 2_000L)

        assertTrue(vibrator.isVibrating)
        assertTrue(notifications.allNotifications.isEmpty())
    }

    @Test
    fun theSameRestAlertsOnce() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        restOver("w-3", endsAt = 3_000L)
        app.getSystemService(NotificationManager::class.java).cancelAll()
        restOver("w-3", endsAt = 3_000L)

        assertTrue(notifications.allNotifications.isEmpty())
    }

    @Test
    fun aBroadcastWithoutAWorkoutIsIgnored() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        RestTimerReceiver().onReceive(app, Intent(RestAlarm.ACTION_REST_OVER).putExtra(RestAlarm.EXTRA_ENDS_AT, 4_000L))

        assertTrue(notifications.allNotifications.isEmpty())
    }
}
