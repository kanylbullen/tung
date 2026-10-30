package io.github.kanylbullen.tung.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import io.github.kanylbullen.tung.web.WebContainer
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/** The one screen on show: loading, the sign-in, or History once signed in. */
@Composable
fun App(container: WebContainer) {
    val strings = container.strings
    when (val phase = container.phase.collectAsState().value) {
        WebContainer.Phase.Loading -> Div({ classes("loading") }) { Text(strings.loading) }
        is WebContainer.Phase.Failed -> Div({ classes("screen") }) {
            Div({ classes("wordmark") }) { Text(strings.appName) }
            P({ classes("error"); attr("role", "alert") }) { Text(strings.storageUnavailable) }
            P({ classes("hint") }) { Text(phase.message) }
        }
        WebContainer.Phase.Ready -> {
            val account = container.settings.account.collectAsState().value
            if (account == null) SignInScreen(container) else HistoryScreen(container, account)
        }
    }
}
