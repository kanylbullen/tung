package io.github.kanylbullen.tung.flows

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
        openSettingsGroup(R.string.settings_group_account)
        waitForText(str(R.string.sync_intro))
        screenshot("1-settings-signed-out")

        // http is fine for the fake: it listens on localhost.
        useServer(fake.baseUrl)
        fill(str(R.string.sync_email), "lifter@example.org")
        fill(str(R.string.sync_password), "correct horse battery")
        clickText(str(R.string.sync_sign_in))

        waitForText(str(R.string.sync_signed_in_as, "lifter@example.org"))
        waitForText(str(R.string.sync_status_never))
        screenshot("2-signed-in")
        assertEquals(listOf("/api/v1/auth/login"), fake.authPaths)
        assertEquals(fake.token, app.tokens.token)
        // Signing in schedules the periodic sync and asks for a first one.
        assertEquals(1, workManager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.PERIODIC_WORK).get().size)
        waitUntil(5_000) { nowWork().isNotEmpty() }

        clickText(str(R.string.sync_now))
        waitForText(str(R.string.sync_status_just_now))
        screenshot("3-synced")
        assertEquals(1, fake.syncRequests.size)
        assertEquals(0L, fake.syncRequests.single().cursor)

        val before = nowWork()
        leaveSettings()
        startWorkoutFromHome()
        addExercise("bench", str(R.string.ex_bench_press))
        enterInSheet(str(R.string.workout_edit_reps, str(R.string.workout_reps_empty)), str(R.string.input_field_reps), "8", str(R.string.workout_edit_reps, "8"))
        clickDescription(str(R.string.workout_mark_done, 1))
        waitForDescription(str(R.string.workout_mark_undone, 1))
        finishWorkout()
        waitForText(str(R.string.summary_title))

        // FinishWorkout, through the command handler, enqueued one more sync.
        waitUntil(5_000) { (nowWork() - before).isNotEmpty() }
        assertTrue(nowWork().containsAll(before))
    }
}
