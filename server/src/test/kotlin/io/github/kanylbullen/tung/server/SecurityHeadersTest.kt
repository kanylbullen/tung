package io.github.kanylbullen.tung.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #157: every answer carries a CSP for its page group and the other browser headers, each once. */
class SecurityHeadersTest {

    private fun HttpResponse.only(name: String): String {
        val values = headers.getAll(name).orEmpty()
        assertEquals(1, values.size, "$name: $values")
        return values.single()
    }

    private fun HttpResponse.assertBrowserHeaders(csp: String) {
        assertEquals(csp, only("Content-Security-Policy"))
        assertEquals("nosniff", only("X-Content-Type-Options"))
        assertEquals("DENY", only("X-Frame-Options"))
        only("Referrer-Policy")
    }

    @Test
    fun theSitePagesGetTheSitePolicyAndRunNoInlineScript() = tungTest {
        for (path in listOf("/", "/privacy", "/terms", "/delete-account")) {
            val page = client.get(path)
            assertEquals(HttpStatusCode.OK, page.status, path)
            page.assertBrowserHeaders(SITE_CSP)
            val html = page.bodyAsText()
            // script-src 'self' blocks an inline script, so the page must not have one.
            assertFalse(Regex("""<script(?![^>]*\ssrc=)[^>]*>""").containsMatchIn(html), "$path has an inline script")
            assertFalse(Regex("""\son[a-z]+\s*=""").containsMatchIn(html), "$path has an inline event handler")
        }
        assertTrue("/site/home.js?v=" in client.get("/").bodyAsText())
        val script = client.get("/site/home.js")
        assertEquals(HttpStatusCode.OK, script.status)
        script.assertBrowserHeaders(SITE_CSP)
    }

    @Test
    fun theEmailLinksPagesGetTheSitePolicyAndKeepTheirReferrerPolicy() = tungTest {
        for (path in listOf("/verify-email?token=nothing", "/reset-password?token=nothing")) {
            val page = client.get(path)
            assertEquals(HttpStatusCode.BadRequest, page.status, path)
            page.assertBrowserHeaders(SITE_CSP)
            assertEquals("no-referrer", page.only("Referrer-Policy"), path)
        }
    }

    @Test
    fun theApiGetsAPolicyThatAllowsNothing() = tungTest {
        val methods = client.get("/api/v1/auth/methods")
        assertEquals(HttpStatusCode.OK, methods.status)
        methods.assertBrowserHeaders(API_CSP)
        // An error from StatusPages too.
        val missing = client.get("/api/v1/nothing-here")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        missing.assertBrowserHeaders(API_CSP)
    }

    @Test
    fun swaggerUiLoadsTheVersionThePolicyNames() = tungTest {
        val docs = client.get("/docs")
        assertEquals(HttpStatusCode.OK, docs.status)
        docs.assertBrowserHeaders(DOCS_CSP)
        // A Ktor upgrade that moves Swagger UI to another version must move the policy with it.
        val html = docs.bodyAsText()
        val loaded = Regex("""https://unpkg\.com/swagger-ui-dist@[^/"]+/""").findAll(html).map { it.value }.toSet()
        assertTrue(loaded.isNotEmpty(), html)
        for (prefix in loaded) assertTrue(prefix in DOCS_CSP, "$prefix is not in the policy")
    }

    @Test
    fun theWebAppGetsTheAppPolicy() {
        val dir = Files.createTempDirectory("tung-web").toFile()
        try {
            File(dir, "index.html").writeText("<!doctype html><title>Tung</title>")
            File(dir, "sw.js").writeText("// worker")
            tungTest(webDir = dir) {
                for (path in listOf("/app/", "/app/sw.js", "/app/history")) {
                    val response = client.get(path)
                    assertEquals(HttpStatusCode.OK, response.status, path)
                    response.assertBrowserHeaders(APP_CSP)
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun strictTransportSecurityOnlyOverHttps() = tungTest(trustProxy = true) {
        assertNull(client.get("/").headers["Strict-Transport-Security"])
        val https = client.get("/") { header("X-Forwarded-Proto", "https") }
        assertEquals("max-age=31536000", https.only("Strict-Transport-Security"))
    }

    @Test
    fun strictTransportSecurityWhenThePublicUrlIsHttps() = testApplication {
        environment { config = MapApplicationConfig() }
        application { module(TestDatabase.config().copy(publicUrl = "https://tung.example.org")) }
        assertEquals("max-age=31536000", client.get("/").only("Strict-Transport-Security"))
    }

    @Test
    fun thePolicyFollowsThePath() {
        assertEquals(APP_CSP, contentSecurityPolicy("/app"))
        assertEquals(APP_CSP, contentSecurityPolicy("/app/index.html"))
        assertEquals(API_CSP, contentSecurityPolicy("/apple"))
        assertEquals(API_CSP, contentSecurityPolicy("/appx/"))
        assertEquals(DOCS_CSP, contentSecurityPolicy("/docs/index.html"))
        assertEquals(SITE_CSP, contentSecurityPolicy("/site/site.css"))
        assertEquals(API_CSP, contentSecurityPolicy("/sitemap.xml"))
        assertEquals(API_CSP, contentSecurityPolicy("/oauth/authorize"))
    }
}
