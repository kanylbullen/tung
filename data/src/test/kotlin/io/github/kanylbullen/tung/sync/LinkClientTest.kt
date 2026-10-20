package io.github.kanylbullen.tung.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.github.kanylbullen.tung.data.TungDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Link codes on the client: the phone asks for one, the watch signs in with it (issue #30). */
@RunWith(RobolectricTestRunner::class)
class LinkClientTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val fake = FakeSyncServer()
    private val databases = mutableListOf<TungDatabase>()
    private var devices = 0

    private object NoScheduler : SyncScheduler {
        override fun schedulePeriodic() = Unit
        override fun enqueueNow() = Unit
        override fun cancel() = Unit
    }

    private inner class Device(token: String?) {
        val db = TungDatabase.buildInMemory(context).also { databases += it }
        val settings = SyncSettings(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "d${devices++}.preferences_pb") })
        val tokens = MemoryTokenStore(token)
        val client = SyncClient(SyncStore(db), settings, tokens, syncHttpClient(), retryDelaysMillis = listOf(10L))
        val account = SyncAccount(client, settings, tokens, SyncStore(db), NoScheduler, scope)
    }

    @Before
    fun start() = fake.start()

    @After
    fun stop() {
        fake.stop()
        databases.forEach { it.close() }
        scope.cancel()
    }

    @Test
    fun theWatchSignsInWithThePhonesCodeOnce() = runBlocking {
        val phone = Device(fake.token)
        phone.settings.signedIn(fake.baseUrl, "lifter@example.org")
        val started = phone.account.startLink()
        assertEquals(LinkStartResult.Success(LinkCode(fake.linkCode, (started as LinkStartResult.Success).code.expiresAt)), started)

        val watch = Device(token = null)
        val claimed = watch.account.signInWithCode(fake.baseUrl, started.code.code)
        assertEquals(LinkClaimResult.Success(fake.token, "lifter@example.org"), claimed)
        assertEquals(fake.token, watch.tokens.read())
        assertTrue(watch.account.isSignedIn())
        assertEquals(fake.baseUrl, watch.settings.serverUrl())
        assertEquals(listOf(fake.baseUrl), watch.settings.recentServers.first())

        val again = Device(token = null).account.signInWithCode(fake.baseUrl, started.code.code)
        assertEquals(LinkClaimResult.Failed(LinkFailure.INVALID_CODE), again)
    }

    @Test
    fun aDeviceWithoutATokenCannotAskForACode() = runBlocking {
        assertEquals(LinkStartResult.Failed(LinkFailure.NOT_SIGNED_IN), Device(token = null).account.startLink())
    }

    @Test
    fun aDeadTokenSignsThePhoneOutWhenItAsksForACode() = runBlocking {
        val phone = Device("tung_revoked")
        phone.settings.signedIn(fake.baseUrl, "lifter@example.org")
        assertEquals(LinkStartResult.Failed(LinkFailure.SIGNED_OUT), phone.account.startLink())
        assertEquals(null, phone.tokens.read())
    }

    @Test
    fun aServerWithoutLinkCodesIsNotAServerForLinking() = runBlocking {
        // FakeSyncServer answers unknown paths 404 not_found, like a tung-server from before link codes.
        val result = Device(token = null).client.claimLink(fake.baseUrl + "/old", "123456")
        assertEquals(LinkClaimResult.Failed(LinkFailure.NOT_A_SERVER), result)
    }
}
