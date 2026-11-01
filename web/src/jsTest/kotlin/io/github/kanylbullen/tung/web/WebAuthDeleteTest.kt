package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.web.sync.DeleteAccountResult
import io.github.kanylbullen.tung.web.sync.HttpReply
import io.github.kanylbullen.tung.web.sync.HttpUnreachable
import io.github.kanylbullen.tung.web.sync.WebAuth
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `DELETE /api/v1/me` from the browser: the body, the token, and each answer read as the phone reads it. */
class WebAuthDeleteTest {

    private fun error(status: Int, code: String) = HttpReply(status, """{"error":{"code":"$code","message":"x"}}""")

    @Test
    fun sendsTheTypedEmailWithTheToken() = runTest {
        var sent = ""
        var auth = ""
        val result = WebAuth(delete = { url, body, headers ->
            assertEquals("https://s/api/v1/me", url)
            sent = body
            auth = headers["Authorization"].orEmpty()
            HttpReply(204, "")
        }).deleteAccount("https://s", "tung_abc", "  x@example.org ")
        assertEquals(DeleteAccountResult.DELETED, result)
        assertTrue(sent.contains("\"email\":\"x@example.org\""), sent)
        assertEquals("Bearer tung_abc", auth)
    }

    @Test
    fun readsEachAnswer() = runTest {
        suspend fun answer(reply: HttpReply) = WebAuth(delete = { _, _, _ -> reply }).deleteAccount("https://s", "t", "x@example.org")
        assertEquals(DeleteAccountResult.EMAIL_MISMATCH, answer(error(400, "email_mismatch")))
        assertEquals(DeleteAccountResult.NOT_ALLOWED, answer(error(403, "session_required")))
        assertEquals(DeleteAccountResult.SIGNED_OUT, answer(error(401, "unauthorized")))
        assertEquals(DeleteAccountResult.NOT_A_SERVER, answer(HttpReply(405, "")))
        assertEquals(DeleteAccountResult.SERVER, answer(HttpReply(500, "")))
        val offline = WebAuth(delete = { _, _, _ -> throw HttpUnreachable(null) }).deleteAccount("https://s", "t", "x@example.org")
        assertEquals(DeleteAccountResult.NETWORK, offline)
    }
}
