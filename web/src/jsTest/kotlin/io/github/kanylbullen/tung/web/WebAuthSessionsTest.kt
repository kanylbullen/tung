package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.core.sync.DEVICE_HEADER
import io.github.kanylbullen.tung.web.sync.ChangePasswordResult
import io.github.kanylbullen.tung.web.sync.HttpReply
import io.github.kanylbullen.tung.web.sync.HttpUnreachable
import io.github.kanylbullen.tung.web.sync.SignOutOthersResult
import io.github.kanylbullen.tung.web.sync.WebAuth
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `POST /api/v1/auth/logout/others` and `/auth/password` from the browser
 * (issue #147): the token, the device, the body, and each answer read as
 * the phone reads it.
 */
class WebAuthSessionsTest {

    private fun error(status: Int, code: String) = HttpReply(status, """{"error":{"code":"$code","message":"x"}}""")

    @Test
    fun signOutOthersSendsTheTokenAndTheDevice() = runTest {
        var headers = emptyMap<String, String>()
        val result = WebAuth(post = { url, _, h ->
            assertEquals("https://s/api/v1/auth/logout/others", url)
            headers = h
            HttpReply(200, """{"revoked_tokens":3}""")
        }).signOutOthers("https://s", "tung_abc", "device-1")
        assertEquals(SignOutOthersResult.Done(3), result)
        assertEquals("Bearer tung_abc", headers["Authorization"])
        assertEquals("device-1", headers[DEVICE_HEADER])
    }

    @Test
    fun signOutOthersReadsEachAnswer() = runTest {
        suspend fun answer(reply: HttpReply) = WebAuth(post = { _, _, _ -> reply }).signOutOthers("https://s", "t", "d")
        assertEquals(SignOutOthersResult.SignedOut, answer(error(401, "unauthorized")))
        assertEquals(SignOutOthersResult.Server, answer(error(403, "session_required")))
        assertEquals(SignOutOthersResult.RateLimited, answer(HttpReply(429, "")))
        assertEquals(SignOutOthersResult.NotAServer, answer(HttpReply(404, "")))
        val offline = WebAuth(post = { _, _, _ -> throw HttpUnreachable(null) }).signOutOthers("https://s", "t", "d")
        assertEquals(SignOutOthersResult.Network, offline)
    }

    @Test
    fun changePasswordSendsBothPasswords() = runTest {
        var sent = ""
        var headers = emptyMap<String, String>()
        val result = WebAuth(post = { url, body, h ->
            assertEquals("https://s/api/v1/auth/password", url)
            sent = body
            headers = h
            HttpReply(204, "")
        }).changePassword("https://s", "tung_abc", "device-1", "old one", "a new one")
        assertEquals(ChangePasswordResult.CHANGED, result)
        assertTrue(sent.contains("\"current_password\":\"old one\""), sent)
        assertTrue(sent.contains("\"new_password\":\"a new one\""), sent)
        assertEquals("Bearer tung_abc", headers["Authorization"])
        assertEquals("device-1", headers[DEVICE_HEADER])
    }

    @Test
    fun changePasswordReadsEachAnswer() = runTest {
        suspend fun answer(reply: HttpReply) = WebAuth(post = { _, _, _ -> reply }).changePassword("https://s", "t", "d", "a", "bbbbbbbb")
        // 403, not 401: a wrong password is not a sign-out.
        assertEquals(ChangePasswordResult.WRONG_PASSWORD, answer(error(403, "wrong_password")))
        assertEquals(ChangePasswordResult.WEAK_PASSWORD, answer(error(400, "weak_password")))
        assertEquals(ChangePasswordResult.NO_PASSWORD, answer(error(409, "no_password")))
        assertEquals(ChangePasswordResult.SIGNED_OUT, answer(error(401, "unauthorized")))
        assertEquals(ChangePasswordResult.RATE_LIMITED, answer(HttpReply(429, "")))
        assertEquals(ChangePasswordResult.SERVER, answer(error(403, "session_required")))
        assertEquals(ChangePasswordResult.NOT_A_SERVER, answer(HttpReply(405, "")))
        val offline = WebAuth(post = { _, _, _ -> throw HttpUnreachable(null) }).changePassword("https://s", "t", "d", "a", "bbbbbbbb")
        assertEquals(ChangePasswordResult.NETWORK, offline)
    }
}
