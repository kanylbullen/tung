package io.github.kanylbullen.tung.flows

import io.github.kanylbullen.tung.ui.resources.*
import android.provider.Settings
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.kanylbullen.tung.BuildConfig
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.sync.FakeSyncServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Settings as a list of groups (issue #67): each row has a live one-line
 * summary and opens its own screen; Account starts from the default server
 * (here [FakeSyncServer], through [FlowTestApp.defaultServer], never the real
 * one) and asks it for its sign-in methods as it opens; Training's default
 * rest is stored.
 */
class SettingsFlowTest : FlowTest() {

    private val fake = FakeSyncServer()

    @Before
    fun startServerAndWorkManager() {
        fake.start()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @After
    fun stopServer() = fake.stop()

    private fun waitForRestDefault(seconds: Int) = waitUntilStored(seconds) { app.container.restTimer.defaultSeconds.first() }

    private fun serverField() = hasSetTextAction() and hasText(str(Res.string.sync_server_url))

    @Test
    fun theGroupsSayWhatTheyAreSetToAndEachOpensItsScreen() {
        clickDescription(str(Res.string.settings_title))
        waitForText(str(Res.string.settings_summary_signed_out))
        waitForText(str(Res.string.settings_summary_devices_signed_out))
        waitForText(str(Res.string.settings_summary_training, "1:30"))
        waitForText(str(Res.string.settings_summary_not_set_up))
        waitForText(str(R.string.settings_summary_health_off))
        waitForText(str(Res.string.settings_summary_data))
        waitForText(str(Res.string.settings_summary_about, BuildConfig.VERSION_NAME, BuildConfig.BUILD_TYPE))
        screenshot("1-groups")

        val screens = listOf(
            Res.string.settings_group_account to str(Res.string.sync_intro),
            Res.string.settings_group_devices to str(Res.string.settings_devices_signed_out),
            Res.string.settings_group_training to str(Res.string.settings_rest_default),
            Res.string.settings_group_assistant to str(Res.string.ai_settings_intro),
            Res.string.settings_group_health to str(R.string.health_write),
            Res.string.settings_group_data to str(Res.string.import_jefit),
            Res.string.settings_group_about to str(Res.string.about_licence_text),
        )
        screens.forEach { (group, shows) ->
            clickText(str(group))
            waitForText(shows)
            screenshot("2-${group.key}")
            clickDescription(str(Res.string.action_back))
            waitForText(str(Res.string.settings_group_about))
        }
        // Signed out, Devices points to Account.
        clickText(str(Res.string.settings_group_devices))
        clickText(str(Res.string.settings_devices_open_account))
        waitForText(str(Res.string.sync_intro))
    }

    @Test
    fun aFreshInstallStartsFromTheDefaultServerAndOffersGoogleAtOnce() {
        app.defaultServer = fake.baseUrl
        fake.googleClientId = "1234-web.apps.googleusercontent.com"
        openSettingsGroup(Res.string.settings_group_account)

        // The default address, read-only, asked at once: nothing was typed.
        waitForText(fake.baseUrl)
        compose.onNode(serverField()).assertDoesNotExist()
        waitForText(str(Res.string.sync_google_sign_in))
        assertTrue(fake.paths.contains("GET /api/v1/auth/methods"))
        // Google first, then email and password.
        val google = compose.onNode(hasText(str(Res.string.sync_google_sign_in))).fetchSemanticsNode().boundsInRoot.top
        val or = compose.onNode(hasText(str(Res.string.sync_google_or_email))).fetchSemanticsNode().boundsInRoot.top
        val email = compose.onNode(hasSetTextAction() and hasText(str(Res.string.sync_email))).fetchSemanticsNode().boundsInRoot.top
        assertTrue("Google ($google) above the or-line ($or) above email ($email)", google < or && or < email)
        screenshot("1-default-server-google")

        // "Use another server" turns the address into a field, starting from the one shown.
        clickText(str(Res.string.sync_use_another_server))
        waitFor(serverField() and hasText(fake.baseUrl))
        screenshot("2-another-server")

        clickText(str(Res.string.sync_google_sign_in))
        waitForText(str(Res.string.sync_signed_in_as, fake.googleEmail))
        assertEquals(listOf("/api/v1/auth/google"), fake.authPaths)
        clickDescription(str(Res.string.action_back))
        waitForText(str(Res.string.sync_signed_in_as, fake.googleEmail))
        screenshot("3-groups-signed-in")
    }

    @Test
    fun aStoredServerOtherThanTheDefaultShowsAsTheField() {
        runBlocking { app.container.syncSettings.signedIn(fake.baseUrl, "lifter@example.org") }
        runBlocking { app.container.syncSettings.signedOut(null) }
        openSettingsGroup(Res.string.settings_group_account)
        waitFor(serverField() and hasText(fake.baseUrl))
        waitUntil { fake.paths.contains("GET /api/v1/auth/methods") }
    }

    @Test
    fun trainingsDefaultRestIsStored() {
        openSettingsGroup(Res.string.settings_group_training)
        waitForText("1:30")
        clickDescription(str(Res.string.rest_longer))
        waitForText("2:00")
        waitForRestDefault(120)
        screenshot("1-rest-2-00")
        repeat(4) { clickDescription(str(Res.string.rest_shorter)) }
        waitForText(str(Res.string.rest_off))
        waitForRestDefault(0)
        clickDescription(str(Res.string.rest_longer))
        waitForRestDefault(30)

        clickDescription(str(Res.string.action_back))
        waitForText(str(Res.string.settings_summary_training, "0:30"))
        clickText(str(Res.string.settings_group_training))
        waitForText("0:30")
    }

    /**
     * Issue #215: without "Alarms & reminders" a rest can end minutes late in
     * the background, so Training says so and offers the system's page;
     * allowed, it says nothing.
     */
    @Test
    fun trainingSaysWhenRestAlertsCanBeLate() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        openSettingsGroup(Res.string.settings_group_training)
        waitForText(str(Res.string.settings_exact_alarms_title))
        screenshot("1-exact-alarms-off")
        clickText(str(Res.string.settings_exact_alarms_allow))
        val opened = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, opened?.action)
        assertEquals("package:${app.packageName}", opened?.data?.toString())

        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        clickDescription(str(Res.string.action_back))
        clickText(str(Res.string.settings_group_training))
        waitForText("1:30")
        assertTrue(compose.onAllNodes(hasText(str(Res.string.settings_exact_alarms_title))).fetchSemanticsNodes().isEmpty())
    }
}

