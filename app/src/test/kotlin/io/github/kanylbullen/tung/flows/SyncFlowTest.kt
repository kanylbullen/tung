package io.github.kanylbullen.tung.flows

import io.github.kanylbullen.tung.ui.resources.*
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.sync.FakeSyncServer
import io.github.kanylbullen.tung.sync.WorkManagerSyncScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Settings and sync as a person meets them: the gear on Home, Account, "Use
 * another server" and its address, sign in against [FakeSyncServer], "Sync now", then a finished
 * workout that asks WorkManager for a sync (WorkManager's test instance: the
 * work is enqueued but, with its network constraint never met, not run).
 */
class SyncFlowTest : FlowTest() {

    private val fake = FakeSyncServer()

    private val workManager: WorkManager get() = WorkManager.getInstance(app)

    @Before
    fun startServerAndWorkManager() {
        fake.start()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @After
    fun stopServer() = fake.stop()

    private fun nowWork(): Set<UUID> =
        workManager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.NOW_WORK).get().map { it.id }.toSet()

    private fun fill(label: String, text: String) {
        val field = hasSetTextAction() and hasText(label)
        waitFor(field)
        compose.onNode(field).performTextReplacement(text)
    }

    @Test
    fun signInSyncNowAndAFinishedWorkoutEnqueuesASync() {
        openSettingsGroup(Res.string.settings_group_account)
        waitForText(str(Res.string.sync_intro))
        screenshot("1-settings-signed-out")

        // http is fine for the fake: it listens on localhost.
        useServer(fake.baseUrl)
        fill(str(Res.string.sync_email), "lifter@example.org")
        fill(str(Res.string.sync_password), "correct horse battery")
        clickText(str(Res.string.sync_sign_in))

        waitForText(str(Res.string.sync_signed_in_as, "lifter@example.org"))
        waitForText(str(Res.string.sync_status_never))
        screenshot("2-signed-in")
        assertEquals(listOf("/api/v1/auth/login"), fake.authPaths)
        assertEquals(fake.token, app.tokens.token)
        // Signing in schedules the periodic sync and asks for a first one.
        assertEquals(1, workManager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.PERIODIC_WORK).get().size)
        waitUntil(5_000) { nowWork().isNotEmpty() }

        clickText(str(Res.string.sync_now))
        waitForText(str(Res.string.sync_status_just_now))
        screenshot("3-synced")
        assertEquals(1, fake.syncRequests.size)
        assertEquals(0L, fake.syncRequests.single().cursor)

        val before = nowWork()
        leaveSettings()
        startWorkoutFromHome()
        addExercise("bench", str(R.string.ex_bench_press))
        enterInSheet(str(Res.string.workout_edit_reps, str(Res.string.workout_reps_empty)), str(Res.string.input_field_reps), "8", str(Res.string.workout_edit_reps, "8"))
        clickDescription(str(Res.string.workout_mark_done, 1))
        waitForDescription(str(Res.string.workout_mark_undone, 1))
        finishWorkout()
        waitForText(str(Res.string.summary_title))

        // FinishWorkout, through the command handler, enqueued one more sync.
        waitUntil(5_000) { (nowWork() - before).isNotEmpty() }
        assertTrue(nowWork().containsAll(before))
    }

    @Test
    fun aNewAccountIsAskedToConfirmItsEmailAndSyncsOnceItHas() {
        fake.emailVerified = false
        openSettingsGroup(Res.string.settings_group_account)
        useServer(fake.baseUrl)
        fill(str(Res.string.sync_email), "new@example.org")
        fill(str(Res.string.sync_password), "correct horse battery")
        clickText(str(Res.string.sync_create_account))
        waitForText(str(Res.string.sync_signed_in_as, "lifter@example.org"))

        // The first sync is refused until the emailed link is opened: the screen says what to do.
        clickText(str(Res.string.sync_now))
        waitForText(str(Res.string.sync_confirm_email, "lifter@example.org"))
        waitForText(str(Res.string.sync_error_email_not_verified))
        screenshot("1-confirm-email")

        clickText(str(Res.string.sync_confirm_resend))
        waitForText(str(Res.string.sync_confirm_sent))
        assertEquals(1, fake.resends)
        screenshot("2-link-sent")

        // The link is opened (on the server); Sync now goes through and the notice goes.
        fake.emailVerified = true
        clickText(str(Res.string.sync_now))
        waitForText(str(Res.string.sync_status_just_now))
        waitGone(hasText(str(Res.string.sync_confirm_email, "lifter@example.org")))
    }

    @Test
    fun forgotPasswordMailsALinkToTheTypedAddress() {
        openSettingsGroup(Res.string.settings_group_account)
        useServer(fake.baseUrl)
        // Without an address, it says to type one.
        clickText(str(Res.string.sync_forgot_password))
        waitForText(str(Res.string.sync_forgot_needs_email))

        fill(str(Res.string.sync_email), "lifter@example.org")
        clickText(str(Res.string.sync_forgot_password))
        waitForText(str(Res.string.sync_forgot_sent, "lifter@example.org"))
        screenshot("1-forgot-sent")
        assertEquals(1, fake.forgotRequests.size)
        assertTrue(fake.forgotRequests.single().contains("\"email\":\"lifter@example.org\""))
        assertTrue(fake.forgotRequests.single().contains("\"language\":\"en\""))
    }
}
