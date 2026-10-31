package io.github.kanylbullen.tung.flows

import io.github.kanylbullen.tung.ui.resources.*
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

    private fun serverField() = hasSetTextAction() and hasText(str(R.string.sync_server_url))

    @Test
    fun theGroupsSayWhatTheyAreSetToAndEachOpensItsScreen() {
        clickDescription(str(R.string.settings_title))
        waitForText(str(R.string.settings_summary_signed_out))
        waitForText(str(R.string.settings_summary_devices_signed_out))
        waitForText(str(R.string.settings_summary_training, "1:30"))
        waitForText(str(R.string.settings_summary_not_set_up))
        waitForText(str(R.string.settings_summary_health_off))
        waitForText(str(R.string.settings_summary_data))
        waitForText(str(R.string.settings_summary_about, BuildConfig.VERSION_NAME, BuildConfig.BUILD_TYPE))
        screenshot("1-groups")

        val screens = listOf(
            R.string.settings_group_account to str(R.string.sync_intro),
            R.string.settings_group_devices to str(R.string.settings_devices_signed_out),
            R.string.settings_group_training to str(R.string.settings_rest_default),
            R.string.settings_group_assistant to str(R.string.ai_settings_intro),
            R.string.settings_group_health to str(R.string.health_write),
            R.string.settings_group_data to str(R.string.import_jefit),
            R.string.settings_group_about to str(R.string.about_licence_text),
        )
        screens.forEach { (group, shows) ->
            clickText(str(group))
            waitForText(shows)
            screenshot("2-${app.resources.getResourceEntryName(group)}")
            clickDescription(str(Res.string.action_back))
            waitForText(str(R.string.settings_group_about))
        }
        // Signed out, Devices points to Account.
        clickText(str(R.string.settings_group_devices))
        clickText(str(R.string.settings_devices_open_account))
        waitForText(str(R.string.sync_intro))
    }

    @Test
    fun aFreshInstallStartsFromTheDefaultServerAndOffersGoogleAtOnce() {
        app.defaultServer = fake.baseUrl
        fake.googleClientId = "1234-web.apps.googleusercontent.com"
        openSettingsGroup(R.string.settings_group_account)

        // The default address, read-only, asked at once: nothing was typed.
        waitForText(fake.baseUrl)
        compose.onNode(serverField()).assertDoesNotExist()
        waitForText(str(R.string.sync_google_sign_in))
        assertTrue(fake.paths.contains("GET /api/v1/auth/methods"))
        // Google first, then email and password.
        val google = compose.onNode(hasText(str(R.string.sync_google_sign_in))).fetchSemanticsNode().boundsInRoot.top
        val or = compose.onNode(hasText(str(R.string.sync_google_or_email))).fetchSemanticsNode().boundsInRoot.top
        val email = compose.onNode(hasSetTextAction() and hasText(str(R.string.sync_email))).fetchSemanticsNode().boundsInRoot.top
        assertTrue("Google ($google) above the or-line ($or) above email ($email)", google < or && or < email)
        screenshot("1-default-server-google")

        // "Use another server" turns the address into a field, starting from the one shown.
        clickText(str(R.string.sync_use_another_server))
        waitFor(serverField() and hasText(fake.baseUrl))
        screenshot("2-another-server")

        clickText(str(R.string.sync_google_sign_in))
        waitForText(str(R.string.sync_signed_in_as, fake.googleEmail))
        assertEquals(listOf("/api/v1/auth/google"), fake.authPaths)
        clickDescription(str(Res.string.action_back))
        waitForText(str(R.string.sync_signed_in_as, fake.googleEmail))
        screenshot("3-groups-signed-in")
    }

    @Test
    fun aStoredServerOtherThanTheDefaultShowsAsTheField() {
        runBlocking { app.container.syncSettings.signedIn(fake.baseUrl, "lifter@example.org") }
        runBlocking { app.container.syncSettings.signedOut(null) }
        openSettingsGroup(R.string.settings_group_account)
        waitFor(serverField() and hasText(fake.baseUrl))
        waitUntil { fake.paths.contains("GET /api/v1/auth/methods") }
    }

    @Test
    fun trainingsDefaultRestIsStored() {
        openSettingsGroup(R.string.settings_group_training)
        waitForText("1:30")
        clickDescription(str(R.string.rest_longer))
        waitForText("2:00")
        waitForRestDefault(120)
        screenshot("1-rest-2-00")
        repeat(4) { clickDescription(str(R.string.rest_shorter)) }
        waitForText(str(R.string.rest_off))
        waitForRestDefault(0)
        clickDescription(str(R.string.rest_longer))
        waitForRestDefault(30)

        clickDescription(str(Res.string.action_back))
        waitForText(str(R.string.settings_summary_training, "0:30"))
        clickText(str(R.string.settings_group_training))
        waitForText("0:30")
    }
}
