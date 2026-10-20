package io.github.kanylbullen.tung.flows

import android.os.Bundle
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.firebase.messaging.RemoteMessage
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.sync.PushEndpointRequest
import io.github.kanylbullen.tung.core.sync.PushTransport
import io.github.kanylbullen.tung.push.TungFirebaseMessagingService
import io.github.kanylbullen.tung.push.TungUnifiedPushReceiver
import io.github.kanylbullen.tung.sync.FakeSyncServer
import io.github.kanylbullen.tung.sync.WorkManagerSyncScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Robolectric
import java.util.UUID

/**
 * Push as the phone does it (issue #34): after sign-in it registers its FCM
 * token or UnifiedPush endpoint with the server under its device id, sends
 * that id with every sync (`X-Tung-Device`), shows the transport in
 * Settings, removes the endpoint when signing out, and turns any push it
 * receives into an enqueued sync. The server is [FakeSyncServer]; the
 * transports are [io.github.kanylbullen.tung.push.FakePushEnvironment].
 */
class PushFlowTest : FlowTest() {

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

    private fun deviceId(): String = runBlocking { app.container.syncSettings.deviceId() }

    private fun fill(label: String, text: String) {
        val field = hasSetTextAction() and hasText(label)
        waitFor(field)
        compose.onNode(field).performTextReplacement(text)
    }

    private fun signIn() {
        clickDescription(str(R.string.settings_title))
        fill(str(R.string.sync_server_url), fake.baseUrl)
        fill(str(R.string.sync_email), "lifter@example.org")
        fill(str(R.string.sync_password), "correct horse battery")
        clickText(str(R.string.sync_sign_in))
        waitForText(str(R.string.sync_signed_in_as, "lifter@example.org"))
    }

    /**
     * A phone with Play services and a Firebase app signs in: its FCM token
     * goes to `POST /api/v1/push/endpoints` with its device id and the
     * session token, Settings says "Push: FCM" and that the server has it,
     * "Sync now" carries the same device id, and signing out deletes the
     * endpoint before the token is revoked.
     */
    @Test
    fun fcmTokenIsRegisteredWithTheDeviceIdAndRemovedOnSignOut() {
        app.push.playServices = true
        app.push.firebaseConfigured = true
        app.push.token = "fcm-token-1"
        app.push.installedDistributors = listOf("io.heckel.ntfy")

        signIn()
        waitUntil { fake.pushRegistrations.isNotEmpty() }
        val (body, auth) = fake.pushRegistrations.first()
        assertEquals(PushEndpointRequest(deviceId(), PushTransport.FCM, "fcm-token-1"), body)
        assertEquals("Bearer ${fake.token}", auth)
        waitForText(str(R.string.push_status_fcm))
        waitForText(str(R.string.push_registered))
        compose.onNode(hasText(str(R.string.push_choose_distributor))).assertDoesNotExist()
        screenshot("1-settings-push-fcm")

        clickText(str(R.string.sync_now))
        waitUntil { fake.syncDevices.isNotEmpty() }
        assertEquals(deviceId(), fake.syncDevices.last())

        clickText(str(R.string.sync_sign_out))
        waitUntil { fake.paths.any { it.endsWith("/api/v1/auth/logout") } }
        assertEquals(listOf(FakeSyncServer.PUSH_ENDPOINT_ID to "Bearer ${fake.token}"), fake.pushDeletes.toList())
        val deleteAt = fake.paths.indexOfFirst { it.startsWith("DELETE /api/v1/push/endpoints/") }
        val logoutAt = fake.paths.indexOfFirst { it.endsWith("/api/v1/auth/logout") }
        assertTrue("the endpoint goes before the token: ${fake.paths}", deleteAt in 0 until logoutAt)
    }

    /**
     * No Play services and two distributors: the first is asked for an
     * endpoint, and when it answers the endpoint goes to the server as
     * `unifiedpush`. Settings names the distributor and the endpoint's host,
     * and offers the other one; choosing it registers with that one.
     */
    @Test
    fun unifiedPushEndpointIsRegisteredAndTheDistributorCanBeChosen() {
        app.push.installedDistributors = listOf("io.heckel.ntfy", "org.example.otherpush")
        app.push.labels = mapOf("io.heckel.ntfy" to "ntfy", "org.example.otherpush" to "Other push")

        signIn()
        waitUntil { app.push.registeredWith.contains("io.heckel.ntfy") }
        // The distributor answers with an endpoint.
        TungUnifiedPushReceiver().onNewEndpoint(app, "https://ntfy.example.org/upAbC123?up=1", "default")
        waitUntil { fake.pushRegistrations.isNotEmpty() }
        assertEquals(
            PushEndpointRequest(deviceId(), PushTransport.UNIFIED_PUSH, "https://ntfy.example.org/upAbC123?up=1"),
            fake.pushRegistrations.first().first,
        )
        waitForText(str(R.string.push_status_unifiedpush, "ntfy"))
        waitForText(str(R.string.push_registered_at, "ntfy.example.org"))
        screenshot("1-settings-push-ntfy")

        clickText(str(R.string.push_choose_distributor))
        clickInDialog("Other push")
        waitUntil { app.push.registeredWith.lastOrNull() == "org.example.otherpush" }
        waitForText(str(R.string.push_status_unifiedpush, "Other push"))
    }

    /** No transport at all: Settings says so, and nothing is registered. */
    @Test
    fun withoutATransportSettingsSaysItSyncsEvery15Minutes() {
        signIn()
        waitForText(str(R.string.push_status_none))
        assertTrue(fake.pushRegistrations.isEmpty())
    }

    /** Whatever arrives, on either transport, is "sync now": each enqueues a sync with WorkManager. */
    @Test
    fun aPushOnEitherTransportEnqueuesASync() {
        signIn()
        waitUntil { nowWork().isNotEmpty() }

        val beforeUnifiedPush = nowWork()
        TungUnifiedPushReceiver().onMessage(app, """{"type":"sync"}""".toByteArray(), "default")
        waitUntil { (nowWork() - beforeUnifiedPush).isNotEmpty() }

        val beforeFcm = nowWork()
        val service = Robolectric.setupService(TungFirebaseMessagingService::class.java)
        service.onMessageReceived(RemoteMessage(Bundle().apply { putString("type", "sync") }))
        waitUntil { (nowWork() - beforeFcm).isNotEmpty() }
    }
}
