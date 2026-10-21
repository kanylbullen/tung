package io.github.kanylbullen.tung.flows

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.auth.GoogleSignInResult
import io.github.kanylbullen.tung.sync.FakeSyncServer
import io.github.kanylbullen.tung.sync.WorkManagerSyncScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * "Sign in with Google" in Settings (issue #51): the button shows only when
 * the server's `/auth/methods` names a client id; Google (the app's
 * [FakeGoogleSignIn]) is asked for a token for that id, the server
 * ([FakeSyncServer]) trades it for a session, and from there it is the
 * password sign-in of [SyncFlowTest]: token stored, sync scheduled.
 */
class GoogleSignInFlowTest : FlowTest() {

    private val fake = FakeSyncServer()

    private val workManager: WorkManager get() = WorkManager.getInstance(app)

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
    fun signInWithGoogleWhenTheServerOffersIt() {
        fake.googleClientId = "1234-web.apps.googleusercontent.com"
        clickDescription(str(R.string.settings_title))
        waitForText(str(R.string.sync_intro))
        fill(str(R.string.sync_server_url), fake.baseUrl)

        waitForText(str(R.string.sync_google_sign_in))
        screenshot("1-google-offered")
        clickText(str(R.string.sync_google_sign_in))

        waitForText(str(R.string.sync_signed_in_as, fake.googleEmail))
        waitForText(str(R.string.sync_google_linked))
        screenshot("2-signed-in-with-google")
        assertEquals(listOf("1234-web.apps.googleusercontent.com"), app.google.requestedClientIds)
        assertEquals(listOf(fake.googleIdToken), fake.googleTokens)
        assertEquals(listOf("/api/v1/auth/google"), fake.authPaths)
        assertEquals(fake.token, app.tokens.token)
        // The rest is the password sign-in's: the periodic sync and a first one.
        assertEquals(1, workManager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.PERIODIC_WORK).get().size)
        waitUntil(5_000) { workManager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.NOW_WORK).get().isNotEmpty() }
    }

    @Test
    fun closingGooglesSheetLeavesTheFormAsItWas() {
        fake.googleClientId = "1234-web.apps.googleusercontent.com"
        app.google.result = GoogleSignInResult.Cancelled
        clickDescription(str(R.string.settings_title))
        fill(str(R.string.sync_server_url), fake.baseUrl)
        clickText(str(R.string.sync_google_sign_in))
        waitUntil { app.google.requestedClientIds.isNotEmpty() }
        waitForText(str(R.string.sync_sign_in))
        assertNull(app.tokens.token)
        assertTrue(fake.googleTokens.isEmpty())

        app.google.result = GoogleSignInResult.NoAccount
        clickText(str(R.string.sync_google_sign_in))
        waitForText(str(R.string.sync_error_google_no_account))
    }

    @Test
    fun noGoogleButtonWhenTheServerDoesNotOfferIt() {
        clickDescription(str(R.string.settings_title))
        waitForText(str(R.string.sync_intro))
        fill(str(R.string.sync_server_url), fake.baseUrl)

        // The server has been asked and has answered; give the answer time to reach the screen.
        waitUntil(5_000) { fake.paths.any { it == "GET /api/v1/auth/methods" } }
        val asked = System.currentTimeMillis()
        waitUntil(5_000) { System.currentTimeMillis() - asked > 1_000 }
        assertTrue(compose.onAllNodes(hasText(str(R.string.sync_google_sign_in))).fetchSemanticsNodes().isEmpty())
        screenshot("1-no-google")
        // The password form is there as always.
        waitForText(str(R.string.sync_sign_in))
    }
}
