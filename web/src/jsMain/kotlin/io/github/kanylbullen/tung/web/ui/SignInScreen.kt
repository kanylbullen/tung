package io.github.kanylbullen.tung.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.kanylbullen.tung.core.sync.ServerUrlCheck
import io.github.kanylbullen.tung.core.sync.SyncFailure
import io.github.kanylbullen.tung.core.sync.checkServerUrl
import io.github.kanylbullen.tung.web.defaultServerUrl
import io.github.kanylbullen.tung.web.WebContainer
import io.github.kanylbullen.tung.web.sync.AuthFailure
import io.github.kanylbullen.tung.web.sync.AuthResult
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.AutoComplete
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.autoComplete
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/**
 * Email and password against a tung-server, the phone's Settings → Account
 * when signed out: the default server is shown read-only under the fields,
 * "Use another server" turns it into a field, and a stored server other
 * than the default is a field from the start. The first sync starts as
 * soon as the sign-in succeeds.
 */
@Composable
fun SignInScreen(container: WebContainer) {
    val strings = container.strings
    val storedServer = container.settings.lastServerUrl
    var email by remember { mutableStateOf(container.settings.lastEmail ?: "") }
    var password by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(storedServer ?: defaultServerUrl()) }
    var editingServer by remember { mutableStateOf(storedServer != null && storedServer != defaultServerUrl()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember {
        mutableStateOf(if (container.signedOutByServer) strings.syncFailure(SyncFailure.SIGNED_OUT) else null)
    }
    val scope = rememberCoroutineScope()

    // Sign in with Google, when the page is served by the server it signs in
    // to: Google only runs its button on origins the server's client id has
    // authorised, and that is the server's own. The client id comes from the
    // server (`/auth/methods`), never from the app.
    val sameOrigin = (checkServerUrl(server) as? ServerUrlCheck.Valid)?.url?.takeIf { it == window.location.origin }
    var googleClientId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sameOrigin) {
        googleClientId = sameOrigin?.let { container.auth.googleClientId(it) }
    }

    fun signInWithGoogle(url: String, idToken: String) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val result = container.signInWithGoogle(url, idToken)
            busy = false
            if (result is AuthResult.Failed) error = strings.authFailure(result.failure)
        }
    }

    fun submit() {
        if (busy) return
        if (email.isBlank() || password.isEmpty()) {
            error = strings.errorFields
            return
        }
        val url = when (val check = checkServerUrl(server)) {
            is ServerUrlCheck.Valid -> check.url
            ServerUrlCheck.Malformed -> {
                error = strings.errorUrl
                return
            }
            ServerUrlCheck.NeedsHttps -> {
                error = strings.errorUrlHttps
                return
            }
        }
        busy = true
        error = null
        scope.launch {
            val result = container.signIn(url, email.trim(), password)
            busy = false
            if (result is AuthResult.Failed) error = strings.authFailure(result.failure)
        }
    }

    Div({ classes("screen") }) {
        Div({ classes("wordmark") }) { Text(strings.appName) }
        P({ classes("intro") }) { Text(strings.signInIntro) }
        val clientId = googleClientId
        val googleUrl = sameOrigin
        if (clientId != null && googleUrl != null) {
            Div({
                classes("google")
                ref { element ->
                    loadGoogleIdentity(
                        ready = {
                            renderGoogleButton(element, clientId, strings.tag) { token -> signInWithGoogle(googleUrl, token) }
                        },
                        failed = { error = strings.authFailure(AuthFailure.GOOGLE_FAILED) },
                    )
                    onDispose { }
                }
            })
            P({ classes("google-hint") }) { Text(strings.googleHint) }
            P({ classes("or") }) { Text(strings.orWithEmail) }
        }
        Form(attrs = {
            addEventListener("submit") {
                it.preventDefault()
                submit()
            }
        }) {
            Div({ classes("field") }) {
                Label(forId = "email") { Text(strings.email) }
                Input(InputType.Email) {
                    id("email")
                    value(email)
                    autoComplete(AutoComplete.username)
                    onInput { email = it.value }
                    if (busy) disabled()
                }
            }
            Div({ classes("field") }) {
                Label(forId = "password") { Text(strings.password) }
                Input(InputType.Password) {
                    id("password")
                    value(password)
                    autoComplete(AutoComplete.currentPassword)
                    onInput { password = it.value }
                    if (busy) disabled()
                }
            }
            if (editingServer) {
                Div({ classes("field") }) {
                    Label(forId = "server") { Text(strings.server) }
                    Input(InputType.Url) {
                        id("server")
                        value(server)
                        attr("placeholder", "https://tung.example.org")
                        attr("inputmode", "url")
                        attr("spellcheck", "false")
                        onInput { server = it.value }
                        if (busy) disabled()
                    }
                }
            } else {
                Div({ classes("field") }) {
                    Label { Text(strings.server) }
                    Div({ classes("readonly") }) { Text(server) }
                    Button({
                        classes("text-button")
                        attr("type", "button")
                        onClick { editingServer = true }
                    }) { Text(strings.useAnotherServer) }
                }
            }
            error?.let { message ->
                P({ classes("error"); attr("role", "alert") }) { Text(message) }
            }
            Button({
                classes("primary")
                if (busy) disabled()
            }) { Text(if (busy) strings.signingIn else strings.signIn) }
        }
    }
}
