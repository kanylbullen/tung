package io.github.kanylbullen.tung.server.routes

import io.ktor.server.routing.post
import io.ktor.server.request.receiveParameters
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.application.ApplicationCall
import io.github.kanylbullen.tung.server.ResetOutcome
import io.github.kanylbullen.tung.server.AUTH_RATE_LIMIT
import io.github.kanylbullen.tung.server.Accounts
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
 * privacy page at `/privacy` and how to delete an account at
 * `/delete-account` (with their
 * font and icon under `/site/`, classpath resources from `site/`), and the
 * web app at `/app/` when [ServerConfig.webDir] is set. The app is one
 * page: every path under `/app/` that is not a file is its `index.html`, and
 * its service worker, served from `/app/`, controls `/app/` only, never the
 * API, `/docs` or the OAuth pages.
 */
fun Route.siteRoutes(config: ServerConfig, accounts: Accounts? = null) {
    // The page itself by a route of its own: a static tree at "/" would also
    // claim every unknown API path and turn its 404 into another answer.
    // The sign-up for the Play test only where that test is run (TUNG_TESTER_SIGNUPS).
    // With a tester group (TUNG_TESTER_GROUP_URL) the form says how to get in at once.
    val group = config.testerGroupUrl.takeIf { config.testerSignups }
    page("/", "index.html") { html ->
        keepBlocks(html, if (config.testerSignups) "testers" else "no-testers", if (config.testerSignups) "no-testers" else "testers")
            .let { keepBlocks(it, if (group != null) "group" else "no-group", if (group != null) "no-group" else "group") }
            .replace("@group@", htmlAttribute(group.orEmpty()))
            .replace("@opt-in@", htmlAttribute(PLAY_OPT_IN_URL))
    }
    // What the site and the apps store; also the privacy policy Google Play asks for.
    page("/privacy", "privacy.html")
    // How to delete an account, and what goes; the deletion link Google Play asks for.
    page("/delete-account", "delete-account.html")
    // The link in a confirmation email. Opening it (a GET, which mail scanners do by themselves) only shows a
    // button; pressing it (the POST) confirms, so only a person at the inbox can.
    if (accounts != null) {
        val confirmPage by lazy { sitePage("verify-email.html") }
        fun confirmView(view: String, token: String = ""): String? {
            var html = confirmPage ?: return null
            for (other in listOf("ask", "confirmed", "failed").filter { it != view }) html = keepBlocks(html, view, other)
            return html.replace("@token@", htmlAttribute(token))
        }
        suspend fun ApplicationCall.respondConfirm(html: String?, status: HttpStatusCode) {
            html ?: return respond(HttpStatusCode.NotFound)
            response.header("Referrer-Policy", "no-referrer")
            response.header(HttpHeaders.CacheControl, "no-store")
            response.header("X-Frame-Options", "DENY")
            response.header("Content-Security-Policy", "frame-ancestors 'none'; form-action 'self'; base-uri 'none'")
            respondText(html, ContentType.Text.Html, status)
        }
        rateLimit(AUTH_RATE_LIMIT) {
            get("/verify-email") {
                val token = call.request.queryParameters["token"]?.takeIf { it.length <= 200 }
                if (token != null && accounts.confirmLinkWorks(token)) {
                    call.respondConfirm(confirmView("ask", token), HttpStatusCode.OK)
                } else {
                    call.respondConfirm(confirmView("failed"), HttpStatusCode.BadRequest)
                }
            }
            post("/verify-email") {
                val token = call.receiveParameters()["token"].orEmpty().take(200)
                if (accounts.confirmEmail(token)) {
                    call.respondConfirm(confirmView("confirmed"), HttpStatusCode.OK)
                } else {
                    call.respondConfirm(confirmView("failed"), HttpStatusCode.BadRequest)
                }
            }
        }
    }

    // The link in a password-reset email: the GET only shows the form (a mail scanner may open it), the POST sets
    // the password. Rate-limited like the API's sign-in routes.
    if (accounts != null) {
        val resetPage by lazy { sitePage("reset-password.html") }
        fun resetView(view: String, token: String = "", weak: Boolean = false): String? {
            var html = resetPage ?: return null
            for (other in listOf("form", "done", "failed").filter { it != view }) html = keepBlocks(html, view, other)
            html = keepBlocks(html, if (weak) "error" else "no-error", if (weak) "no-error" else "error")
            return html.replace("@token@", htmlAttribute(token))
        }
        suspend fun ApplicationCall.respondReset(html: String?, status: HttpStatusCode) {
            html ?: return respond(HttpStatusCode.NotFound)
            response.header("Referrer-Policy", "no-referrer")
            response.header(HttpHeaders.CacheControl, "no-store")
            response.header("X-Frame-Options", "DENY")
            response.header("Content-Security-Policy", "frame-ancestors 'none'; form-action 'self'; base-uri 'none'")
            respondText(html, ContentType.Text.Html, status)
        }
        rateLimit(AUTH_RATE_LIMIT) {
            get("/reset-password") {
                val token = call.request.queryParameters["token"]?.takeIf { it.length <= 200 }
                if (token != null && accounts.resetLinkWorks(token)) {
                    call.respondReset(resetView("form", token), HttpStatusCode.OK)
                } else {
                    call.respondReset(resetView("failed"), HttpStatusCode.BadRequest)
                }
            }
            post("/reset-password") {
                val form = call.receiveParameters()
                val token = form["token"].orEmpty().take(200)
                when (accounts.resetPassword(token, form["password"].orEmpty())) {
                    ResetOutcome.DONE -> call.respondReset(resetView("done"), HttpStatusCode.OK)
                    ResetOutcome.WEAK_PASSWORD -> call.respondReset(resetView("form", token, weak = true), HttpStatusCode.BadRequest)
                    ResetOutcome.INVALID_LINK -> call.respondReset(resetView("failed"), HttpStatusCode.BadRequest)
                }
            }
        }
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

/**
 * A short hash of the stylesheet and script every page links, put into their
 * URLs (`site.css?v=…`): a CDN in front of the server (the instance's
 * Cloudflare keeps static files for hours) then never pairs a new page with
 * an old stylesheet, since a change to either file changes the URLs.
 */
private val assetVersion: String by lazy {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf("site.css", "site.js", "theme.js").forEach { name ->
        SiteRoutes::class.java.getResourceAsStream("/site/$name")?.use { digest.update(it.readBytes()) }
    }
    digest.digest().joinToString("") { "%02x".format(it) }.take(12)
}

/**
 * [html] with the blocks named [keep] (`<!-- keep:begin -->` … `<!-- keep:end -->`)
 * left in without their markers, and those named [drop] taken out whole.
 */
internal fun keepBlocks(html: String, keep: String, drop: String): String {
    fun marker(name: String, end: String) = Regex("""[ \t]*<!-- ${Regex.escape(name)}:$end -->\n?""")
    val dropped = Regex("""[ \t]*<!-- ${Regex.escape(drop)}:begin -->[\s\S]*?<!-- ${Regex.escape(drop)}:end -->\n?""").replace(html, "")
    return marker(keep, "end").replace(marker(keep, "begin").replace(dropped, ""), "")
}

/** The classpath's `site/[file]` with `@assets@` as [assetVersion], or null when it is missing. */
private fun sitePage(file: String): String? =
    SiteRoutes::class.java.getResource("/site/$file")?.readText()?.replace("@assets@", assetVersion)

/** One page of the site at [path], read from the classpath's `site/[file]`, with `@assets@` as [assetVersion] and then [transform]. */
private fun Route.page(path: String, file: String, transform: (String) -> String = { it }) {
    val page by lazy { sitePage(file)?.let(transform) }
    get(path) {
        val html = page ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondText(html, ContentType.Text.Html)
    }
}

/** [value] safe inside a double-quoted HTML attribute. */
private fun htmlAttribute(value: String): String =
    value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
