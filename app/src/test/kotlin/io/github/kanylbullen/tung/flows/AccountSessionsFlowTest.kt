package io.github.kanylbullen.tung.flows

import io.github.kanylbullen.tung.ui.resources.*
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.kanylbullen.tung.sync.FakeSyncServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Settings → Account → "Sign out other devices…" and "Change password…"
 * (issue #147): each asks first, talks to [FakeSyncServer] with this
 * device's id, and leaves this phone signed in. A wrong current password is
 * said under its field and signs nothing out.
 */
class AccountSessionsFlowTest : FlowTest() {

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

    private fun signIn() {
        openSettingsGroup(Res.string.settings_group_account)
        waitForText(str(Res.string.sync_intro))
        useServer(fake.baseUrl)
        fill(str(Res.string.sync_email), "lifter@example.org")
        fill(str(Res.string.sync_password), "correct horse")
        clickText(str(Res.string.sync_sign_in))
        waitForText(str(Res.string.sync_signed_in_as, "lifter@example.org"))
    }

    @Test
    fun signingOutOtherDevicesKeepsThisOne() {
        signIn()
        clickText(str(Res.string.sync_sign_out_others))
        waitForText(str(Res.string.sync_sign_out_others_title))
        waitForText(str(Res.string.sync_sign_out_others_body))
        screenshot("1-dialog")

        clickText(str(Res.string.sync_sign_out_others_confirm))
        waitForText(plural(Res.plurals.sync_sign_out_others_done, 2, 2))
        screenshot("2-done")
        assertEquals(1, fake.signOutOthersDevices.size)
        assertFalse(fake.signOutOthersDevices.single().isNullOrBlank())
        assertEquals(fake.token, app.tokens.token)
        waitForText(str(Res.string.sync_signed_in_as, "lifter@example.org"))

        clickText(str(Res.string.sync_sign_out_others))
        clickText(str(Res.string.sync_sign_out_others_confirm))
        waitForText(str(Res.string.sync_sign_out_others_none))
    }

    @Test
    fun changingThePasswordAsksForTheCurrentOne() {
        signIn()
        clickText(str(Res.string.sync_change_password))
        waitForText(str(Res.string.sync_change_password_title))
        fill(str(Res.string.sync_change_password_current), "guess")
        fill(str(Res.string.sync_change_password_new), "short")
        clickText(str(Res.string.sync_change_password_confirm))
        // Checked here before anything is sent.
        waitForText(str(Res.string.sync_error_weak_password))
        assertFalse("/api/v1/auth/password" in fake.authPaths)

        fill(str(Res.string.sync_change_password_new), "a much better one")
        clickText(str(Res.string.sync_change_password_confirm))
        waitForText(str(Res.string.sync_change_password_error_wrong))
        screenshot("1-wrong-password")
        // A 403 is not a sign-out.
        assertEquals(fake.token, app.tokens.token)
        assertEquals("correct horse", fake.password)

        fill(str(Res.string.sync_change_password_current), "correct horse")
        clickText(str(Res.string.sync_change_password_confirm))
        waitForText(str(Res.string.sync_change_password_done))
        screenshot("2-changed")
        assertEquals("a much better one", fake.password)
        assertEquals(fake.token, app.tokens.token)
        waitForText(str(Res.string.sync_signed_in_as, "lifter@example.org"))
    }

    @Test
    fun anAccountWithoutAPasswordIsNotOfferedToChangeIt() {
        fake.hasPassword = false
        signIn()
        waitForText(str(Res.string.sync_sign_out_others))
        assertEquals(0, compose.onAllNodes(hasText(str(Res.string.sync_change_password))).fetchSemanticsNodes().size)
    }
}
