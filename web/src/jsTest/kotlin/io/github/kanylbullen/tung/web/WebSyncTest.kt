package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.core.sync.DEVICE_HEADER
import io.github.kanylbullen.tung.core.sync.SYNC_MAX_ROWS
import io.github.kanylbullen.tung.core.sync.SyncChanges
import io.github.kanylbullen.tung.core.sync.SyncFailure
import io.github.kanylbullen.tung.core.sync.SyncRequest
import io.github.kanylbullen.tung.core.sync.SyncResponse
import io.github.kanylbullen.tung.core.sync.SyncResult
import io.github.kanylbullen.tung.core.sync.SyncRoutine
import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.github.kanylbullen.tung.web.store.IndexedDbStore
import io.github.kanylbullen.tung.web.store.TungStore
import io.github.kanylbullen.tung.web.sync.AuthFailure
import io.github.kanylbullen.tung.web.sync.AuthResult
import io.github.kanylbullen.tung.web.sync.HttpReply
import io.github.kanylbullen.tung.web.sync.ForgotResult
import io.github.kanylbullen.tung.web.sync.HttpUnreachable
import io.github.kanylbullen.tung.web.sync.ResendResult
import io.github.kanylbullen.tung.web.sync.SyncJson
import io.github.kanylbullen.tung.web.sync.WebAuth
import io.github.kanylbullen.tung.web.sync.WebSettings
import io.github.kanylbullen.tung.web.sync.WebSync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The sync loop against a scripted server (the transport handed in), over a
 * real IndexedDB: the order of operations, the cursor, pulling again while
 * a response is full, retries, and the 401 that signs out.
 */
class WebSyncTest {

    /** One request the fake server saw. */
    private class Seen(val url: String, val headers: Map<String, String>, val request: SyncRequest)

    /** A server that answers from a script, one reply per request, and records what it saw. */
    private class FakeServer(vararg replies: () -> HttpReply) {
        private val replies: List<() -> HttpReply> = replies.toList()
        private var answered = 0
        val seen = ArrayList<Seen>()

        val post: suspend (String, String, Map<String, String>) -> HttpReply = { url, body, headers ->
            seen += Seen(url, headers, SyncJson.decodeFromString(SyncRequest.serializer(), body))
            val reply = this.replies.getOrNull(answered++) ?: error("no reply left for request ${seen.size}")
            reply()
        }

        companion object {
            fun ok(response: SyncResponse): () -> HttpReply = { HttpReply(200, SyncJson.encodeToString(SyncResponse.serializer(), response)) }
        }
    }

    private suspend fun signedIn(name: String): Pair<IndexedDbStore, WebSettings> {
        IndexedDbStore.delete(name)
        val db = IndexedDbStore.open(name)
        val settings = WebSettings(db).also { it.load() }
        settings.signedIn("https://tung.example.org", "x@example.org", "tung_token")
        return db to settings
    }

    private fun sync(store: TungStore, db: IndexedDbStore, settings: WebSettings, server: FakeServer, onSignedOut: suspend () -> Unit = {}) =
        WebSync(store, db, settings, clock = { 1_000L }, retryDelaysMillis = listOf(0L, 0L), onSignedOut = onSignedOut, post = server.post)

    @Test
    fun pushesTheDirtyRowsAppliesTheAnswerAndStoresTheCursor() = runTest {
        val name = "tung-sync-1-${kotlin.js.Date.now().toLong()}"
        val (db, settings) = signedIn(name)
        val store = TungStore()
        store.routines.put(SyncRoutine("r1", "Push", null, updatedAt = 5), dirty = true)
        val fromServer = SyncWorkout("w1", startedAt = 1, endedAt = 2, updatedAt = 7)
        val server = FakeServer(FakeServer.ok(SyncResponse(42, SyncChanges(workouts = listOf(fromServer)))))

        val result = sync(store, db, settings, server).syncOnce()

        assertEquals(SyncResult.Success(pushed = 1, applied = 1), result)
        val seen = server.seen.single()
        assertEquals("https://tung.example.org/api/v1/sync", seen.url)
        assertEquals("Bearer tung_token", seen.headers["Authorization"])
        assertEquals(settings.deviceId(), seen.headers[DEVICE_HEADER])
        assertEquals(0, seen.request.cursor, "the first sync starts at 0")
        assertEquals(listOf("r1"), seen.request.changes.routines.map { it.id })
        assertFalse(store.routines.rows.getValue("r1").dirty, "cleared: the pushed row still has its updatedAt")
        assertNotNull(store.workouts.get("w1"))
        assertEquals(42, settings.syncState.value.cursor)
        assertEquals(1_000L, settings.syncState.value.lastSyncAt)
        assertNull(settings.syncState.value.lastError)

        // What the next page load reads.
        val reloaded = TungStore()
        db.loadInto(reloaded)
        assertNotNull(reloaded.workouts.get("w1"))
        assertFalse(reloaded.routines.rows.getValue("r1").dirty)
        assertEquals("42", db.meta("cursor"))
        db.close()
        IndexedDbStore.delete(name)
    }

    @Test
    fun pullsAgainWhileAResponseIsFullWithTheLatestCursor() = runTest {
        val name = "tung-sync-2-${kotlin.js.Date.now().toLong()}"
        val (db, settings) = signedIn(name)
        val store = TungStore()
        val fullPage = SyncChanges(workouts = List(SYNC_MAX_ROWS) { SyncWorkout("w$it", startedAt = 1, endedAt = 2, updatedAt = 3) })
        val server = FakeServer(
            FakeServer.ok(SyncResponse(100, fullPage)),
            FakeServer.ok(SyncResponse(101, SyncChanges(workouts = listOf(SyncWorkout("last", startedAt = 1, endedAt = 2, updatedAt = 3))))),
        )

        val result = sync(store, db, settings, server).syncOnce()

        assertEquals(SyncResult.Success(pushed = 0, applied = SYNC_MAX_ROWS + 1), result)
        assertEquals(listOf(0L, 100L), server.seen.map { it.request.cursor })
        assertEquals(101, settings.syncState.value.cursor)
        db.close()
        IndexedDbStore.delete(name)
    }

    @Test
    fun retriesAServerErrorAndTheNetworkButNeverARefusal() = runTest {
        val name = "tung-sync-3-${kotlin.js.Date.now().toLong()}"
        val (db, settings) = signedIn(name)
        val store = TungStore()
        val recovering = FakeServer(
            { HttpReply(503, "") },
            { throw HttpUnreachable(null) },
            FakeServer.ok(SyncResponse(1, SyncChanges())),
        )
        assertEquals(SyncResult.Success(0, 0), sync(store, db, settings, recovering).syncOnce())
        assertEquals(3, recovering.seen.size)

        val refusing = FakeServer({ HttpReply(409, """{"error":{"code":"missing_parent"}}""") })
        assertEquals(SyncResult.Failed(SyncFailure.REJECTED), sync(store, db, settings, refusing).syncOnce())
        assertEquals(1, refusing.seen.size, "a 4xx is not retried")
        assertEquals(SyncFailure.REJECTED.key, settings.syncState.value.lastError)

        val unconfirmed = FakeServer({ HttpReply(403, """{"error":{"code":"email_not_verified","message":"Confirm your email first."}}""") })
        assertEquals(SyncResult.Failed(SyncFailure.EMAIL_NOT_VERIFIED), sync(store, db, settings, unconfirmed).syncOnce())
        assertEquals(1, unconfirmed.seen.size, "waiting for the link is not retried")
        assertEquals(SyncFailure.EMAIL_NOT_VERIFIED.key, settings.syncState.value.lastError)

        val down = FakeServer({ HttpReply(500, "") }, { HttpReply(500, "") }, { HttpReply(500, "") })
        assertEquals(SyncResult.Failed(SyncFailure.SERVER), sync(store, db, settings, down).syncOnce())
        assertEquals(3, down.seen.size, "two retries, then it gives up")
        db.close()
        IndexedDbStore.delete(name)
    }

    @Test
    fun aFourOhOneSignsTheBrowserOut() = runTest {
        val name = "tung-sync-4-${kotlin.js.Date.now().toLong()}"
        val (db, settings) = signedIn(name)
        val store = TungStore()
        var told = false
        val server = FakeServer({ HttpReply(401, "") })

        val result = sync(store, db, settings, server, onSignedOut = { told = true }).syncOnce()

        assertEquals(SyncResult.Failed(SyncFailure.SIGNED_OUT), result)
        assertTrue(told)
        assertNull(settings.account.value)
        assertNull(settings.token)
        assertNull(db.meta("token"), "the token is gone from the browser")
        assertEquals("https://tung.example.org", settings.lastServerUrl, "the server and email stay for the next sign-in")
        assertEquals(SyncFailure.SIGNED_OUT.key, settings.syncState.value.lastError)
        assertEquals(SyncResult.NotSignedIn, sync(store, db, settings, server).syncOnce())
        db.close()
        IndexedDbStore.delete(name)
    }

    @Test
    fun signInReadsTheSessionAndNamesEachFailure() = runTest {
        suspend fun answer(reply: () -> HttpReply): AuthResult {
            var body = ""
            val auth = WebAuth(post = { _, b, _ -> body = b; reply() })
            val result = auth.signIn("https://tung.example.org", "x@example.org", "secret123")
            if (result is AuthResult.Success) assertTrue(body.contains("\"secret123\""))
            return result
        }
        assertEquals(
            AuthResult.Success("tung_abc", "X@example.org"),
            answer { HttpReply(200, """{"token":"tung_abc","user":{"id":"u","email":"X@example.org","created_at":1,"has_password":true,"google_linked":false}}""") },
        )
        assertEquals(AuthResult.Failed(AuthFailure.INVALID_CREDENTIALS), answer { HttpReply(401, "") })
        assertEquals(AuthResult.Failed(AuthFailure.RATE_LIMITED), answer { HttpReply(429, "") })
        assertEquals(AuthResult.Failed(AuthFailure.NOT_A_SERVER), answer { HttpReply(404, "<html>") })
        assertEquals(AuthResult.Failed(AuthFailure.INVALID_REQUEST), answer { HttpReply(400, """{"error":{"code":"invalid_email"}}""") })
        assertEquals(AuthResult.Failed(AuthFailure.SERVER), answer { HttpReply(502, "") })
        assertEquals(AuthResult.Failed(AuthFailure.SERVER), answer { HttpReply(200, "not json") })
        assertEquals(AuthResult.Failed(AuthFailure.NETWORK), answer { throw HttpUnreachable(null) })
        assertIs<AuthResult.Failed>(answer { HttpReply(401, "") })
    }

    @Test
    fun registerPostsTheLanguageAndNamesEachFailure() = runTest {
        var url = ""
        var body = ""
        suspend fun answer(reply: () -> HttpReply): AuthResult {
            val auth = WebAuth(post = { u, b, _ -> url = u; body = b; reply() })
            return auth.register("https://tung.example.org", "x@example.org", "secret123", "sv")
        }
        assertEquals(
            AuthResult.Success("tung_new", "x@example.org"),
            answer { HttpReply(201, """{"token":"tung_new","user":{"id":"u","email":"x@example.org","created_at":1,"has_password":true,"google_linked":false}}""") },
        )
        assertEquals("https://tung.example.org/api/v1/auth/register", url)
        assertEquals("""{"email":"x@example.org","password":"secret123","language":"sv"}""", body)
        assertEquals(AuthResult.Failed(AuthFailure.ACCOUNT_EXISTS), answer { HttpReply(409, """{"error":{"code":"email_taken"}}""") })
        assertEquals(AuthResult.Failed(AuthFailure.WEAK_PASSWORD), answer { HttpReply(400, """{"error":{"code":"weak_password","message":"…"}}""") })
        assertEquals(AuthResult.Failed(AuthFailure.INVALID_REQUEST), answer { HttpReply(400, """{"error":{"code":"invalid_email"}}""") })
        assertEquals(AuthResult.Failed(AuthFailure.RATE_LIMITED), answer { HttpReply(429, "") })
        assertEquals(AuthResult.Failed(AuthFailure.NETWORK), answer { throw HttpUnreachable(null) })
    }

    @Test
    fun signInSendsNoLanguage() = runTest {
        var body = ""
        WebAuth(post = { _, b, _ -> body = b; HttpReply(401, "") }).signIn("https://tung.example.org", "x@example.org", "secret123")
        assertEquals("""{"email":"x@example.org","password":"secret123"}""", body)
    }

    @Test
    fun resendConfirmationPostsTheLanguageAndNamesTheOutcome() = runTest {
        var url = ""
        var body = ""
        var auth: String? = null
        suspend fun answer(reply: () -> HttpReply): ResendResult =
            WebAuth(post = { u, b, h -> url = u; body = b; auth = h["Authorization"]; reply() })
                .resendConfirmation("https://tung.example.org", "tung_abc", "sv")
        assertEquals(ResendResult.SENT, answer { HttpReply(202, "") })
        assertEquals("https://tung.example.org/api/v1/auth/verify/resend", url)
        assertEquals("""{"language":"sv"}""", body)
        assertEquals("Bearer tung_abc", auth)
        assertEquals(ResendResult.RATE_LIMITED, answer { HttpReply(429, "") })
        assertEquals(ResendResult.FAILED, answer { HttpReply(404, """{"error":{"code":"not_configured"}}""") })
        assertEquals(ResendResult.NETWORK, answer { throw HttpUnreachable(null) })
    }

    @Test
    fun forgotPasswordPostsTheEmailAndNamesTheOutcome() = runTest {
        var url = ""
        var body = ""
        suspend fun answer(reply: () -> HttpReply): ForgotResult =
            WebAuth(post = { u, b, _ -> url = u; body = b; reply() }).forgotPassword("https://tung.example.org", "x@example.org", "sv")
        assertEquals(ForgotResult.SENT, answer { HttpReply(202, "") })
        assertEquals("https://tung.example.org/api/v1/auth/password/forgot", url)
        assertEquals("""{"email":"x@example.org","language":"sv"}""", body)
        assertEquals(ForgotResult.NOT_OFFERED, answer { HttpReply(404, """{"error":{"code":"not_configured"}}""") })
        assertEquals(ForgotResult.RATE_LIMITED, answer { HttpReply(429, "") })
        assertEquals(ForgotResult.INVALID_EMAIL, answer { HttpReply(400, """{"error":{"code":"invalid_email"}}""") })
        assertEquals(ForgotResult.NETWORK, answer { throw HttpUnreachable(null) })
    }
}
