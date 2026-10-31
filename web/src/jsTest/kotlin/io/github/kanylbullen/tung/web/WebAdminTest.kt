package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.web.admin.AdminResult
import io.github.kanylbullen.tung.web.admin.WebAdmin
import io.github.kanylbullen.tung.web.sync.HttpReply
import io.github.kanylbullen.tung.web.sync.HttpUnreachable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Settings → Server admin: who is an admin, the account list, and the Play test sign-ups, each read with the token. */
class WebAdminTest {

    private fun admin(reply: (String) -> HttpReply) = WebAdmin(get = { url, headers ->
        assertEquals("Bearer t", headers["Authorization"])
        reply(url)
    })

    @Test
    fun onlyMeSayingSoMakesAnAdmin() = runTest {
        assertTrue(admin { HttpReply(200, """{"id":"u","email":"a@b.c","is_admin":true}""") }.isAdmin("https://s", "t"))
        assertFalse(admin { HttpReply(200, """{"id":"u","email":"a@b.c","is_admin":false}""") }.isAdmin("https://s", "t"))
        assertFalse(admin { HttpReply(200, """{"id":"u","email":"a@b.c"}""") }.isAdmin("https://s", "t"), "a server from before the field")
        assertFalse(admin { throw HttpUnreachable(null) }.isAdmin("https://s", "t"))
    }

    @Test
    fun theAccountsAndTheSignUps() = runTest {
        val users = admin { url ->
            assertEquals("https://s/api/v1/admin/users", url)
            HttpReply(
                200,
                """{"user_count":2,"tester_signups":true,"users":[
                  {"id":"1","email":"new@x.org","created_at":20,"has_password":false,"google_linked":true,"is_admin":false,
                   "last_seen_at":null,"active_tokens":0,"workouts":0,"last_workout_at":null},
                  {"id":"2","email":"me@x.org","created_at":10,"has_password":true,"google_linked":false,"is_admin":true,
                   "last_seen_at":30,"active_tokens":2,"workouts":14,"last_workout_at":25}]}""",
            )
        }.users("https://s", "t")
        val value = (users as AdminResult.Ok).value
        assertEquals(2, value.userCount)
        assertTrue(value.testerSignups)
        assertEquals(listOf("new@x.org", "me@x.org"), value.users.map { it.email })
        assertEquals(14, value.users[1].workouts)

        val testers = admin { HttpReply(200, """{"testers":[{"email":"t@x.org","created_at":5,"language":"sv"}]}""") }.testers("https://s", "t")
        assertEquals("sv", (testers as AdminResult.Ok).value.single().language)
    }

    @Test
    fun refusalsByName() = runTest {
        assertEquals(AdminResult.NotAdmin, admin { HttpReply(403, """{"error":{"code":"not_admin","message":"x"}}""") }.users("https://s", "t"))
        assertEquals(AdminResult.SignedOut, admin { HttpReply(401, "") }.users("https://s", "t"))
        assertEquals(AdminResult.Failed, admin { HttpReply(500, "") }.users("https://s", "t"))
        assertEquals(AdminResult.Failed, admin { HttpReply(200, "not json") }.users("https://s", "t"))
        assertEquals(AdminResult.Failed, admin { throw HttpUnreachable(null) }.testers("https://s", "t"))
    }

    @Test
    fun removingASignUpNamesTheAddressInThePath() = runTest {
        var asked = ""
        val removed = WebAdmin(get = { _, _ -> HttpReply(500, "") }, delete = { url, headers ->
            assertEquals("Bearer t", headers["Authorization"])
            asked = url
            HttpReply(204, "")
        }).removeTester("https://s", "t", "a+b@x.org")
        assertTrue(removed)
        assertEquals("https://s/api/v1/testers/a%2Bb%40x.org", asked)
    }
}
