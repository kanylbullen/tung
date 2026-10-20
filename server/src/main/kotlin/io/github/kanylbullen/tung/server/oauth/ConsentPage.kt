package io.github.kanylbullen.tung.server.oauth

/**
 * The one HTML page the server has: log in and allow (or deny) an MCP client.
 * Plain HTML and CSS in the app's colours, no JavaScript, nothing loaded from
 * elsewhere. Every value is escaped; the hidden fields carry the
 * authorization request through the POST.
 */
object ConsentPage {

    data class Request(
        val clientId: String,
        val clientName: String,
        val redirectUri: String,
        val state: String?,
        val scope: String,
        val codeChallenge: String,
        val resource: String?,
    )

    fun render(request: Request, csrf: String, email: String = "", error: String? = null): String {
        val name = escape(request.clientName)
        val writes = OAuthScopes.WRITE in request.scope.split(' ')
        val what = if (writes) "read and change your training log" else "read your training log"
        val target = runCatching { java.net.URI(request.redirectUri).let { it.host ?: it.scheme } }.getOrNull() ?: request.redirectUri
        val hidden = listOfNotNull(
            "response_type" to "code",
            "client_id" to request.clientId,
            "redirect_uri" to request.redirectUri,
            request.state?.let { "state" to it },
            "scope" to request.scope,
            "code_challenge" to request.codeChallenge,
            "code_challenge_method" to "S256",
            request.resource?.let { "resource" to it },
            "csrf" to csrf,
        ).joinToString("\n") { (key, value) -> """      <input type="hidden" name="$key" value="${escape(value)}">""" }
        return page(
            title = "Connect $name to Tung",
            body = """
    <h1>Connect $name to Tung</h1>
    <p><strong>$name</strong> wants to $what.</p>
    ${error?.let { """<p class="error" role="alert">${escape(it)}</p>""" }.orEmpty()}
    <form method="post" action="/oauth/authorize">
$hidden
      <label for="email">Email</label>
      <input id="email" name="email" type="email" autocomplete="username" required value="${escape(email)}">
      <label for="password">Password</label>
      <input id="password" name="password" type="password" autocomplete="current-password" required>
      <div class="actions">
        <button type="submit" name="action" value="allow" class="primary">Allow</button>
        <button type="submit" name="action" value="deny" formnovalidate>Deny</button>
      </div>
    </form>
    <p class="muted">Allowing sends you back to ${escape(target)}. You can end this access at any time by revoking
    the API token named <strong>mcp:$name</strong> in Tung.</p>""",
        )
    }

    /** A dead end: no redirect is safe (unknown client, unregistered redirect URI, expired form). */
    fun error(message: String): String = page(
        title = "Cannot connect",
        body = """
    <h1>Cannot connect</h1>
    <p>${escape(message)}</p>
    <p class="muted">Go back to the app you came from and connect again.</p>""",
    )

    private fun page(title: String, body: String): String = """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="referrer" content="no-referrer">
  <title>$title</title>
  <style>
    :root { --bg: #1A1D21; --raised: #23272D; --border: #2F343B; --text: #ECE7DD; --muted: #9AA0A6; --accent: #F2C230; --accent-text: #F2C230; --on-accent: #1A1D21; --error: #F28B82; }
    @media (prefers-color-scheme: light) {
      :root { --bg: #F6F6F4; --raised: #FFFFFF; --border: #E2E1DC; --text: #1A1D21; --muted: #5F6368; --accent: #B98900; --accent-text: #8A6500; --on-accent: #1A1D21; --error: #B3261E; }
    }
    * { box-sizing: border-box; }
    body { margin: 0; background: var(--bg); color: var(--text); font: 16px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
    main { max-width: 26rem; margin: 3rem auto; padding: 1.5rem; background: var(--raised); border: 1px solid var(--border); border-radius: 12px; }
    h1 { font-size: 1.4rem; margin: 0 0 1rem; }
    label { display: block; margin: 1rem 0 0.25rem; }
    input[type=email], input[type=password] { width: 100%; padding: 0.7rem; font: inherit; color: var(--text); background: var(--bg); border: 1px solid var(--border); border-radius: 12px; }
    .actions { display: flex; gap: 0.75rem; margin-top: 1.5rem; }
    button { flex: 1; padding: 0.8rem; font: inherit; font-weight: 600; border-radius: 12px; border: 1px solid var(--border); background: var(--raised); color: var(--text); cursor: pointer; }
    button.primary { background: var(--accent); border-color: var(--accent); color: var(--on-accent); }
    .muted { color: var(--muted); font-size: 0.9rem; }
    .error { color: var(--error); }
    strong { color: var(--accent-text); }
  </style>
</head>
<body>
  <main>$body
  </main>
</body>
</html>
"""

    fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }
}
