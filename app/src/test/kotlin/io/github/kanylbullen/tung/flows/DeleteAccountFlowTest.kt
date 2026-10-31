package io.github.kanylbullen.tung.flows

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.sync.FakeSyncServer
import io.github.kanylbullen.tung.sync.WorkManagerSyncScheduler
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Settings → Account → "Delete account…": the dialog says what goes and
 * what stays, a wrong email is refused under the field, the account's email
 * deletes it on [FakeSyncServer], and the phone is signed out with its log kept.
 */
class DeleteAccountFlowTest : FlowTest() {

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

    @Test
    fun deletingTheAccountAsksForTheEmailAndSignsOut() {
        openSettingsGroup(R.string.settings_group_account)
        waitForText(str(R.string.sync_intro))
        useServer(fake.baseUrl)
        fill(str(R.string.sync_email), "lifter@example.org")
        fill(str(R.string.sync_password), "correct horse battery")
        clickText(str(R.string.sync_sign_in))
        waitForText(str(R.string.sync_signed_in_as, "lifter@example.org"))

        clickText(str(R.string.sync_delete_account))
        waitForText(str(R.string.sync_delete_title))
        waitForText(str(R.string.sync_delete_body, fake.baseUrl))
        screenshot("1-dialog")

        fill(str(R.string.sync_delete_email), "someone@example.org")
        clickText(str(R.string.sync_delete_confirm))
        waitForText(str(R.string.sync_delete_error_email))
        screenshot("2-wrong-email")
        assertFalse(fake.accountDeleted)

        fill(str(R.string.sync_delete_email), "Lifter@Example.org")
        clickText(str(R.string.sync_delete_confirm))
        waitForText(str(R.string.sync_delete_done))
        screenshot("3-deleted")
        assertTrue(fake.accountDeleted)
        assertNull(app.tokens.token)
        waitForText(str(R.string.sync_intro))
        // No logout call: the token went with the account.
        assertFalse("/api/v1/auth/logout" in fake.authPaths)
        val periodic = WorkManager.getInstance(app).getWorkInfosForUniqueWork(WorkManagerSyncScheduler.PERIODIC_WORK).get()
        assertTrue(periodic.all { it.state == WorkInfo.State.CANCELLED })
    }
}
