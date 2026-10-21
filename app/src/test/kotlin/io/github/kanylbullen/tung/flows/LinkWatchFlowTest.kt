package io.github.kanylbullen.tung.flows

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.sync.FakeSyncServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * "Link a watch" (issue #30): signed in against [FakeSyncServer], Settings
 * asks the server for a link code and shows it large, for the watch to type.
 */
class LinkWatchFlowTest : FlowTest() {

    private val fake = FakeSyncServer()

    @Before
    fun startServerAndWorkManager() {
        fake.start()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @After
    fun stopServer() = fake.stop()

    private fun fill(label: String, text: String) {
        val field = hasSetTextAction() and hasText(label)
        waitFor(field)
        compose.onNode(field).performTextReplacement(text)
    }

    /** Devices may scroll. */
    private fun scrollAndClick(text: String) {
        waitForText(text)
        compose.onNode(hasText(text)).performScrollTo().performClick()
    }

    @Test
    fun settingsShowsASixDigitCodeForTheWatch() {
        openSettingsGroup(R.string.settings_group_account)
        useServer(fake.baseUrl)
        fill(str(R.string.sync_email), "lifter@example.org")
        fill(str(R.string.sync_password), "correct horse battery")
        clickText(str(R.string.sync_sign_in))
        waitForText(str(R.string.sync_signed_in_as, "lifter@example.org"))

        // The watch is in Devices, next to Account.
        clickDescription(str(R.string.action_back))
        clickText(str(R.string.settings_group_devices))
        scrollAndClick(str(R.string.link_watch))
        waitForText("042 917")
        waitForText(str(R.string.link_watch_hint))
        screenshot("1-link-code")
        assertEquals(listOf("/api/v1/auth/login", "/api/v1/link/start"), fake.authPaths)

        scrollAndClick(str(R.string.link_watch_done))
        waitForText(str(R.string.link_watch))
    }
}
