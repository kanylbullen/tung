package io.github.kanylbullen.tung.flows

import io.github.kanylbullen.tung.ui.resources.*
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.command.RoutinePlan
import io.github.kanylbullen.tung.core.command.RoutinePlanExercise
import io.github.kanylbullen.tung.core.command.id
import io.github.kanylbullen.tung.core.sync.SyncTable
import io.github.kanylbullen.tung.sync.FakeSyncServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.robolectric.shadows.ShadowDialog

/**
 * A shared phone (issue #152): a sign-in as the account the data on the
 * phone belongs to, or the first one, uploads it as before; another
 * account is asked "This device has workouts from another account" before
 * anything is uploaded, and each answer does what it says.
 */
class AccountSwitchFlowTest : FlowTest() {

    private val fake = FakeSyncServer()

    @Before
    fun startServerAndWorkManager() {
        fake.start()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @After
    fun stopServer() = fake.stop()

    /** A's routine, logged before anyone signs in. */
    private fun aRoutine(): String = runBlocking {
        checkNotNull(
            app.container.commands.execute(
                Command.SaveRoutinePlan(RoutinePlan("Alpha day", listOf(RoutinePlanExercise(ExerciseRef.ByKey("bench_press"), targetSets = 3, targetReps = 8)))),
            ).id(CommandIds.ROUTINE_ID),
        )
    }

    private fun routines(): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM routine").use { c -> c.moveToFirst(); c.getInt(0) }

    private fun fill(label: String, text: String) {
        val field = hasSetTextAction() and hasText(label)
        waitFor(field)
        compose.onNode(field).performTextReplacement(text)
    }

    private fun signIn(email: String) {
        fill(str(Res.string.sync_email), email)
        fill(str(Res.string.sync_password), "correct horse battery")
        clickText(str(Res.string.sync_sign_in))
    }

    /** Signs in as A (the fake's first account), syncs, and signs out: A's routine is on the server and on the phone. */
    private fun aSignsInSyncsAndSignsOut() {
        openSettingsGroup(Res.string.settings_group_account)
        useServer(fake.baseUrl)
        signIn("lifter@example.org")
        waitForText(str(Res.string.sync_signed_in_as, "lifter@example.org"))
        clickText(str(Res.string.sync_now))
        waitForText(str(Res.string.sync_status_just_now))
        clickText(str(Res.string.sync_sign_out))
        waitForText(str(Res.string.sync_sign_in))
    }

    @Test
    fun aFirstSignInUploadsWithoutAsking() {
        val routine = aRoutine()
        openSettingsGroup(Res.string.settings_group_account)
        useServer(fake.baseUrl)
        signIn("lifter@example.org")
        waitForText(str(Res.string.sync_signed_in_as, "lifter@example.org"))
        waitGone(hasText(str(Res.string.sync_other_account_title)))
        clickText(str(Res.string.sync_now))
        waitForText(str(Res.string.sync_status_just_now))
        assertNotNull(fake.stored(SyncTable.ROUTINES, routine))
    }

    @Test
    fun theSameAccountAgainIsNotAsked() {
        aRoutine()
        aSignsInSyncsAndSignsOut()
        signIn("lifter@example.org")
        waitForText(str(Res.string.sync_signed_in_as, "lifter@example.org"))
        waitGone(hasText(str(Res.string.sync_other_account_title)))
        assertEquals(1, routines())
    }

    @Test
    fun anotherAccountChoosesToDeleteThem() {
        aRoutine()
        aSignsInSyncsAndSignsOut()
        fake.switchAccount("b", "b@example.org")
        val pushedBefore = fake.syncRequests.size

        signIn("b@example.org")
        waitForText(str(Res.string.sync_other_account_title))
        screenshot("1-other-account")
        // Nothing is stored or synced while the question is open.
        assertNull(app.tokens.token)
        assertEquals(pushedBefore, fake.syncRequests.size)

        clickText(str(Res.string.sync_other_account_delete))
        waitForText(str(Res.string.sync_signed_in_as, "b@example.org"))
        waitUntil { routines() == 0 }
        screenshot("2-deleted")
        clickText(str(Res.string.sync_now))
        waitForText(str(Res.string.sync_status_just_now))
        // Nothing of A's went up to B.
        assertEquals(0, fake.storedCount())
    }

    @Test
    fun anotherAccountChoosesToAddThem() {
        val routine = aRoutine()
        aSignsInSyncsAndSignsOut()
        fake.switchAccount("b", "b@example.org")

        signIn("b@example.org")
        waitForText(str(Res.string.sync_other_account_title))
        clickText(str(Res.string.sync_other_account_add, "b@example.org"))
        waitForText(str(Res.string.sync_signed_in_as, "b@example.org"))
        clickText(str(Res.string.sync_now))
        waitForText(str(Res.string.sync_status_just_now))
        assertEquals(1, routines())
        assertNotNull(fake.stored(SyncTable.ROUTINES, routine))
    }

    @Test
    fun dismissingTheQuestionSignsOutAgainAndChangesNothing() {
        aRoutine()
        aSignsInSyncsAndSignsOut()
        fake.switchAccount("b", "b@example.org")
        val logouts = fake.authPaths.count { it == "/api/v1/auth/logout" }

        signIn("b@example.org")
        waitForText(str(Res.string.sync_other_account_title))
        // Back, as a person dismisses a dialog: the dialog window's own back dispatcher.
        compose.runOnUiThread {
            @Suppress("DEPRECATION")
            ShadowDialog.getLatestDialog().onBackPressed()
        }
        waitGone(hasText(str(Res.string.sync_other_account_title)))
        waitUntil { fake.authPaths.count { it == "/api/v1/auth/logout" } == logouts + 1 }
        waitForText(str(Res.string.sync_sign_in))
        assertNull(app.tokens.token)
        assertEquals(1, routines())
        assertEquals(0, fake.storedCount())
    }
}
