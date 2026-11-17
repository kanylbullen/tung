package io.github.kanylbullen.tung.server

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.plugins.origin
import io.ktor.server.request.path

/**
 * The browser-facing security headers every answer gets (issue #157): a
 * Content-Security-Policy chosen by the path's page group, and
 * `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy` and, over
 * https, `Strict-Transport-Security`.
 *
 * A route that sets a header itself keeps its own value: the OAuth consent
 * page has a CSP of its own that widens only when Google's button is on it,
 * and a stricter referrer policy. Nothing here is ever sent twice, since two
 * CSP headers are both enforced and their intersection would break that page.
 *
 * The web app (`/app/`) shares the origin with every other page, and the
 * app keeps the session token and the assistant's key in IndexedDB and
 * localStorage, which are per origin, not per path. So every page group is
 * as strict as it can be, not only the app.
 */
val SecurityHeaders = createApplicationPlugin("SecurityHeaders", ::SecurityHeadersConfig) {
    val httpsPublicUrl = pluginConfig.publicUrl?.startsWith("https://", ignoreCase = true) == true
    onCallRespond { call, _ -> call.applySecurityHeaders(httpsPublicUrl) }
}

class SecurityHeadersConfig {
    /** `TUNG_PUBLIC_URL`: when it is https, HSTS goes out even if the proxy in front does not say https. */
    var publicUrl: String? = null
}

private fun ApplicationCall.applySecurityHeaders(httpsPublicUrl: Boolean) {
    val headers = response.headers
    fun setIfAbsent(name: String, value: String) {
        if (headers[name] == null) headers.append(name, value)
    }
    setIfAbsent(CSP_HEADER, contentSecurityPolicy(request.path()))
    setIfAbsent("X-Content-Type-Options", "nosniff")
    setIfAbsent("X-Frame-Options", "DENY")
    setIfAbsent("Referrer-Policy", "strict-origin-when-cross-origin")
    // Browsers ignore it over http, and a self-hosted server on the home network may well be http, so only when
    // the server is public over https or this request came over https (through a trusted proxy); without
    // includeSubDomains, so the instance never decides for other hosts of its domain.
    if (httpsPublicUrl || request.origin.scheme == "https") setIfAbsent("Strict-Transport-Security", "max-age=31536000")
}

const val CSP_HEADER = "Content-Security-Policy"

/** What no page needs, whatever it is: plugins, a `<base>`, being framed, a form posting elsewhere. */
private const val LOCKED = "object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"

/**
 * The web app. Its own code is `tung.js` (no eval: webpack's production
 * build, checked 2026-11-16), plus the two sign-in libraries it loads on
 * demand (`GoogleButton.kt`, `AppleButton.kt`). Styles may be inline: Compose
 * HTML, Google's button and Apple's set them. Images are the app's, the
 * feedback screenshots (`data:` while picked, `blob:` in Server admin) and
 * the Apple logo's mask in `styles.css`. `connect-src` names any https and
 * http origin: the app syncs with a server the user chooses and asks the AI
 * provider the user set up, and core's `checkServerUrl` (https, or http to
 * localhost and private addresses) is the gate a CSP cannot express.
 */
internal const val APP_CSP =
    "default-src 'self'; " +
        "script-src 'self' https://accounts.google.com/gsi/client https://appleid.cdn-apple.com; " +
        "style-src 'self' 'unsafe-inline' https://accounts.google.com/gsi/style; " +
        "img-src 'self' data: blob:; " +
        "font-src 'self'; " +
        "connect-src 'self' https: http:; " +
        "frame-src https://accounts.google.com/gsi/ https://appleid.apple.com; " +
        "worker-src 'self'; manifest-src 'self'; " +
        LOCKED

/**
 * The site (`/`, `/privacy`, `/delete-account`, the email links' pages and
 * `/site/`): its own scripts only, no inline one (the landing page's is
 * `home.js`). Inline styles are allowed because the landing page's ruler
 * places its ticks with `style` attributes.
 */
internal const val SITE_CSP =
    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; " +
        "font-src 'self'; connect-src 'self'; " +
        LOCKED

/**
 * Swagger UI at `/docs`: Ktor's page loads `swagger-ui-dist` 5.17.12 from
 * unpkg and starts it with an inline script, which it writes itself. The
 * version is pinned in the plugin, so the policy names that path only; a
 * Ktor upgrade that moves it shows as an empty `/docs`.
 */
internal const val DOCS_CSP =
    "default-src 'self'; " +
        "script-src 'self' 'unsafe-inline' https://unpkg.com/swagger-ui-dist@5.17.12/; " +
        "style-src 'self' 'unsafe-inline' https://unpkg.com/swagger-ui-dist@5.17.12/; " +
        "img-src 'self' data:; connect-src 'self'; " +
        LOCKED

/** Everything else: JSON, the OAuth metadata, a screenshot's bytes. Nothing in it may run or load anything. */
internal const val API_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'"

internal fun contentSecurityPolicy(path: String): String = when {
    path == "/app" || path.startsWith("/app/") -> APP_CSP
    path == "/docs" || path.startsWith("/docs/") -> DOCS_CSP
    path in SITE_PAGES || path.startsWith("/site/") -> SITE_CSP
    else -> API_CSP
}

private val SITE_PAGES = setOf("/", "/privacy", "/delete-account", "/verify-email", "/reset-password")
