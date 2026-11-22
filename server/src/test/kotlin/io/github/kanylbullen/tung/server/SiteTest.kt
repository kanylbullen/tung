package io.github.kanylbullen.tung.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Every page of the site ([SITE_PAGES]): served, and their stylesheet and script under a versioned URL a CDN cannot keep stale. */
class SiteTest {

    @Test
    fun thePagesLinkTheirAssetsByAVersion() = tungTest {
        val versions = SITE_PAGES.map { path ->
            val response = client.get(path)
            // An email link's page without a token shows its "the link does not work" view.
            val expected = if (path in EMAIL_LINK_PAGES) HttpStatusCode.BadRequest else HttpStatusCode.OK
            assertEquals(expected, response.status, path)
            val html = response.bodyAsText()
            assertFalse("@assets@" in html, "$path: the token is replaced")
            val css = Regex("""/site/site\.css\?v=([0-9a-f]{12})""").find(html)?.groupValues?.get(1)
            val js = Regex("""/site/site\.js\?v=([0-9a-f]{12})""").find(html)?.groupValues?.get(1)
            assertTrue(css != null && css == js, "$path: $css $js")
            css
        }
        assertEquals(1, versions.toSet().size, "one version for every page")
        assertEquals(HttpStatusCode.OK, client.get("/site/site.css?v=${versions.first()}").status)
    }
}
