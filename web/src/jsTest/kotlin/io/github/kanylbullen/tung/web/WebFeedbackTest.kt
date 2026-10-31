package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.web.admin.AdminResult
import io.github.kanylbullen.tung.web.admin.WebAdmin
import io.github.kanylbullen.tung.web.feedback.FeedbackKind
import io.github.kanylbullen.tung.web.feedback.FeedbackResult
import io.github.kanylbullen.tung.web.feedback.WebFeedback
import io.github.kanylbullen.tung.web.sync.HttpReply
import io.github.kanylbullen.tung.web.sync.HttpUnreachable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Feedback from the web: what is sent with the token, each answer by name, and the admins' list and resolve. */
class WebFeedbackTest {

    @Test
    fun theBodyNamesTheWebAndCarriesTheScreenshots() = runTest {
        var sentUrl = ""
        var sentBody = ""
        val feedback = WebFeedback(post = { url, body, headers ->
            assertEquals("Bearer t", headers["Authorization"])
            sentUrl = url
            sentBody = body
            HttpReply(201, """{"id":"f"}""")
        })
        val result = feedback.send("https://s", "t", FeedbackKind.IDEA, "  Dark mode  ", listOf("data:image/png;base64,AAAA"), "abc-1")
        assertEquals(FeedbackResult.SENT, result)
        assertEquals("https://s/api/v1/feedback", sentUrl)
        val body = Json.parseToJsonElement(sentBody).jsonObject
        assertEquals("idea", body["kind"]!!.jsonPrimitive.content)
        assertEquals("Dark mode", body["message"]!!.jsonPrimitive.content)
        assertEquals("web", body["platform"]!!.jsonPrimitive.content)
        assertEquals("abc-1", body["app_version"]!!.jsonPrimitive.content)
        assertEquals(1, body["images"]!!.jsonArray.size)
    }

    @Test
    fun eachRefusalHasItsName() = runTest {
        suspend fun answer(reply: () -> HttpReply) =
            WebFeedback(post = { _, _, _ -> reply() }).send("https://s", "t", FeedbackKind.BUG, "x", emptyList(), null)
        assertEquals(FeedbackResult.TOO_LARGE, answer { HttpReply(413, """{"error":{"code":"image_too_large","message":"m"}}""") })
        assertEquals(FeedbackResult.TOO_MANY_TODAY, answer { HttpReply(429, """{"error":{"code":"too_much_feedback","message":"m"}}""") })
        assertEquals(FeedbackResult.SIGNED_OUT, answer { HttpReply(401, "") })
        assertEquals(FeedbackResult.FAILED, answer { HttpReply(500, "") })
        assertEquals(FeedbackResult.NETWORK, answer { throw HttpUnreachable(null) })
    }

    @Test
    fun theAdminsListAndResolve() = runTest {
        var patched = ""
        val admin = WebAdmin(
            get = { url, _ ->
                assertEquals("https://s/api/v1/admin/feedback", url)
                HttpReply(
                    200,
                    """{"open_count":1,"feedback":[{"id":"f1","kind":"bug","message":"Crash","platform":"android","app_version":"0.1",
                       "created_at":5,"resolved_at":null,"user_id":"u","email":"a@b.c","images":[{"id":"i1","content_type":"image/png","size":10}]}]}""",
                )
            },
            patch = { url, body, _ ->
                patched = "$url $body"
                HttpReply(204, "")
            },
        )
        val list = (admin.feedback("https://s", "t") as AdminResult.Ok).value
        assertEquals(1, list.openCount)
        assertEquals("i1", list.feedback.single().images.single().id)
        assertTrue(admin.setResolved("https://s", "t", "f1", resolved = true))
        assertEquals("""https://s/api/v1/admin/feedback/f1 {"resolved":true}""", patched)
    }
}
