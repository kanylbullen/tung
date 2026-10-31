package io.github.kanylbullen.tung.flows

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Settings → Feedback: signed out it points to Account; signed in a bug or
 * an idea goes to `POST /api/v1/feedback` with the account's token, and a
 * refusal is said in words. The photo picker is the system's, so the
 * screenshots are not driven here.
 */
class FeedbackFlowTest : FlowTest() {

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
        compose.onNode(field).performScrollTo().performTextReplacement(text)
    }

    private fun scrollAndClick(text: String) {
        val node = hasText(text) and isEnabled()
        waitFor(node)
        compose.onNode(node).performScrollTo().performClick()
    }

    @Test
    fun anIdeaReachesTheServerWithTheAccount() {
        openSettingsGroup(R.string.settings_group_feedback)
        waitForText(str(R.string.feedback_signed_out))
        screenshot("1-signed-out")
        clickText(str(R.string.settings_devices_open_account))

        useServer(fake.baseUrl)
        fill(str(R.string.sync_email), "lifter@example.org")
        fill(str(R.string.sync_password), "correct horse battery")
        clickText(str(R.string.sync_sign_in))
        waitForText(str(R.string.sync_signed_in_as, "lifter@example.org"))
        clickDescription(str(R.string.action_back))
        clickText(str(R.string.settings_group_feedback))

        clickText(str(R.string.feedback_idea))
        fill(str(R.string.feedback_message), "A plate calculator in the weight sheet")
        screenshot("2-written")
        scrollAndClick(str(R.string.feedback_send))
        waitForText(str(R.string.feedback_sent))
        screenshot("3-sent")

        waitUntil { fake.feedback.size == 1 }
        val body = fake.feedback.single()
        assertTrue(body, "\"kind\":\"idea\"" in body)
        assertTrue(body, "\"platform\":\"android\"" in body)
        assertTrue(body, "A plate calculator in the weight sheet" in body)
        assertTrue(body, "\"images\":[]" in body)
        assertEquals("/api/v1/feedback", fake.authPaths.last())

        // The form is empty again, and a refusal is said, not swallowed.
        fake.feedbackStatus = 429
        fill(str(R.string.feedback_message), "Another")
        scrollAndClick(str(R.string.feedback_send))
        waitForText(str(R.string.feedback_too_many))
    }
}
