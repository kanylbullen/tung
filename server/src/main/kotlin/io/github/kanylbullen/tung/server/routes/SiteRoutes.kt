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

/** Anchors the class loader the landing page is read with. */
private object SiteRoutes

/**
 * What a browser sees outside the API: the landing page at `/` (with its
 * font and icon under `/site/`, classpath resources from `site/`), and the
 * web app at `/app/` when [ServerConfig.webDir] is set. The app is one
 * page: every path under `/app/` that is not a file is its `index.html`, and
 * its service worker, served from `/app/`, controls `/app/` only, never the
 * API, `/docs` or the OAuth pages.
 */
fun Route.siteRoutes(config: ServerConfig) {
    // The page itself by a route of its own: a static tree at "/" would also
    // claim every unknown API path and turn its 404 into another answer.
    get("/") {
        val page = SiteRoutes::class.java.getResource("/site/index.html")?.readText()
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondText(page, ContentType.Text.Html)
    }
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
