package io.github.kanylbullen.tung.server.routes

import io.github.kanylbullen.tung.server.ServerConfig
import io.ktor.http.ContentType
import io.ktor.server.http.content.staticFiles
import io.ktor.server.http.content.staticResources
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.security.MessageDigest

/** Anchors the class loader the landing page is read with. */
private object SiteRoutes

/**
 * What a browser sees outside the API: the landing page at `/` and the
 * privacy page at `/privacy` (with their
 * font and icon under `/site/`, classpath resources from `site/`), and the
 * web app at `/app/` when [ServerConfig.webDir] is set. The app is one
 * page: every path under `/app/` that is not a file is its `index.html`, and
 * its service worker, served from `/app/`, controls `/app/` only, never the
 * API, `/docs` or the OAuth pages.
 */
fun Route.siteRoutes(config: ServerConfig) {
    // The page itself by a route of its own: a static tree at "/" would also
    // claim every unknown API path and turn its 404 into another answer.
    page("/", "index.html")
    // What the site and the apps store; also the privacy policy Google Play asks for.
    page("/privacy", "privacy.html")
    staticResources("/site", "site")

    val webDir = config.webDir ?: return
    // The app's paths are relative; without the slash they would resolve against /.
    get("/app") { call.respondRedirect("/app/", permanent = true) }
    staticFiles("/app", webDir, index = "index.html") {
        default("index.html")
        contentType { file -> if (file.extension == "webmanifest") ContentType("application", "manifest+json") else null }
        // The worker and the shell must be revalidated, or a new build would wait for the cache to expire.
        modify { file, call ->
            if (file.name == "sw.js" || file.name == "index.html") call.response.header(HttpHeaders.CacheControl, "no-cache")
        }
    }
}

/**
 * A short hash of the stylesheet and script every page links, put into their
 * URLs (`site.css?v=…`): a CDN in front of the server (the instance's
 * Cloudflare keeps static files for hours) then never pairs a new page with
 * an old stylesheet, since a change to either file changes the URLs.
 */
private val assetVersion: String by lazy {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf("site.css", "site.js").forEach { name ->
        SiteRoutes::class.java.getResourceAsStream("/site/$name")?.use { digest.update(it.readBytes()) }
    }
    digest.digest().joinToString("") { "%02x".format(it) }.take(12)
}

/** One page of the site at [path], read from the classpath's `site/[file]`, with `@assets@` as [assetVersion]. */
private fun Route.page(path: String, file: String) {
    val page by lazy { SiteRoutes::class.java.getResource("/site/$file")?.readText()?.replace("@assets@", assetVersion) }
    get(path) {
        val html = page ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondText(html, ContentType.Text.Html)
    }
}
