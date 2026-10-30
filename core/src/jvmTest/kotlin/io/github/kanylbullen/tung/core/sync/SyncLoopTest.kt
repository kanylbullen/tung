package io.github.kanylbullen.tung.core.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The loop every client runs, over fakes: what it sends, in what order, and what it records. */
class SyncLoopTest {

    private class FakeLocal(var dirty: SyncChanges = SyncChanges()) : SyncLocalStore {
        val applied = ArrayList<Pair<SyncResponse, SyncChanges>>()
        override suspend fun readDirty() = LocalChanges(dirty, SyncChanges())
        override suspend fun apply(response: SyncResponse, pushed: SyncChanges): Int {
            applied += response to pushed
            return response.changes.size()
        }
    }

    private class FakeSession(var token: String? = "tung_t") : SyncSession {
        var cursor = 0L
        var lastSuccess: Long? = null
        var lastFailure: SyncFailure? = null
        var signedOut = false
        override suspend fun serverUrl() = "https://s"
        override suspend fun token() = token
        override suspend fun deviceId() = "dev"
        override suspend fun cursor() = cursor
        override suspend fun setCursor(cursor: Long) { this.cursor = cursor }
        override suspend fun recordSuccess(at: Long) { lastSuccess = at; lastFailure = null }
        override suspend fun recordFailure(failure: SyncFailure) { lastFailure = failure }
        override suspend fun signedOutByServer() { signedOut = true; token = null; lastFailure = SyncFailure.SIGNED_OUT }
    }

    private class Script(vararg replies: SyncReply) : SyncTransport {
        private val replies = replies.toList()
        val requests = ArrayList<SyncRequest>()
        override suspend fun post(baseUrl: String, token: String, deviceId: String, request: SyncRequest): SyncReply {
            assertEquals("https://s", baseUrl)
            assertEquals("dev", deviceId)
            requests += request
            return replies[requests.size - 1]
        }
    }

    private fun loop(local: FakeLocal, session: FakeSession, transport: SyncTransport) =
        SyncLoop(local, session, transport, clock = { 42L }, retryDelaysMillis = listOf(0L, 0L))

    private fun answer(cursor: Long, changes: SyncChanges = SyncChanges()) = SyncReply.Answer(SyncResponse(cursor, changes))

    @Test
    fun pushesAppliesAndStoresTheCursor() = runBlocking {
        val routine = SyncRoutine("r1", "Push", null, 5)
        val local = FakeLocal(SyncChanges(routines = listOf(routine)))
        val session = FakeSession().apply { cursor = 7 }
        val transport = Script(answer(9, SyncChanges(workouts = listOf(SyncWorkout("w", 1, 2, updatedAt = 3)))))
        assertEquals(SyncResult.Success(pushed = 1, applied = 1), loop(local, session, transport).syncOnce())
        assertEquals(7L, transport.requests.single().cursor)
        assertEquals(listOf(routine), local.applied.single().second.routines)
        assertEquals(9L, session.cursor)
        assertEquals(42L, session.lastSuccess)
    }

    @Test
    fun pullsAgainWhileAPageIsFull() = runBlocking {
        val full = SyncChanges(sets = List(SYNC_MAX_ROWS) { SyncSet("s$it", "we", it, 0.0, 1, true, 1) })
        val session = FakeSession()
        val transport = Script(answer(100, full), answer(101))
        assertEquals(SyncResult.Success(0, SYNC_MAX_ROWS), loop(FakeLocal(), session, transport).syncOnce())
        assertEquals(listOf(0L, 100L), transport.requests.map { it.cursor })
        assertEquals(101L, session.cursor)
    }

    @Test
    fun retriesServerErrorsAndTheNetworkButNotARefusal() = runBlocking {
        val recovering = Script(SyncReply.Status(503), SyncReply.Unreachable, answer(1))
        assertEquals(SyncResult.Success(0, 0), loop(FakeLocal(), FakeSession(), recovering).syncOnce())
        assertEquals(3, recovering.requests.size)

        val session = FakeSession()
        val refusing = Script(SyncReply.Status(409))
        assertEquals(SyncResult.Failed(SyncFailure.REJECTED), loop(FakeLocal(), session, refusing).syncOnce())
        assertEquals(1, refusing.requests.size)
        assertEquals(SyncFailure.REJECTED, session.lastFailure)

        val unreadable = Script(SyncReply.Unreadable, SyncReply.Unreadable, SyncReply.Unreadable)
        assertEquals(SyncResult.Failed(SyncFailure.SERVER), loop(FakeLocal(), FakeSession(), unreadable).syncOnce())
        assertEquals(3, unreadable.requests.size)

        val throwing = SyncTransport { _, _, _, _ -> error("boom") }
        assertEquals(SyncResult.Failed(SyncFailure.NETWORK), loop(FakeLocal(), FakeSession(), throwing).syncOnce())
    }

    @Test
    fun aFourOhOneSignsOutAndTellsTheCaller() = runBlocking {
        val session = FakeSession()
        var told = false
        val result = SyncLoop(FakeLocal(), session, Script(SyncReply.Status(401)), clock = { 0L }, onSignedOut = { told = true }).syncOnce()
        assertEquals(SyncResult.Failed(SyncFailure.SIGNED_OUT), result)
        assertTrue(session.signedOut && told)
        assertNull(session.lastSuccess)
        assertEquals(SyncResult.NotSignedIn, loop(FakeLocal(), session, Script()).syncOnce())
    }
}
