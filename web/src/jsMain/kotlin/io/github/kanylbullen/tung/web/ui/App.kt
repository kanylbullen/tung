package io.github.kanylbullen.tung.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.kanylbullen.tung.web.WebContainer
import io.github.kanylbullen.tung.web.sync.Account
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Nav
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.events.Event

/** The screens by the location's hash: `#/` Home, `#/history` History, `#/workout` the workout in progress; back and forward work. */
enum class Route(val hash: String) {
    HOME("#/"),
    HISTORY("#/history"),
    WORKOUT("#/workout"),
    ;

    companion object {
        fun of(hash: String): Route = entries.firstOrNull { it.hash == hash } ?: HOME
    }
}

/** The one screen on show: loading, the sign-in, or Home and History once signed in. */
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
            if (account == null) SignInScreen(container) else SignedIn(container, account)
        }
    }
}

@Composable
private fun SignedIn(container: WebContainer, account: Account) {
    var route by remember { mutableStateOf(Route.of(window.location.hash)) }
    DisposableEffect(Unit) {
        val listener: (Event) -> Unit = { route = Route.of(window.location.hash) }
        window.addEventListener("hashchange", listener)
        onDispose { window.removeEventListener("hashchange", listener) }
    }
    when (route) {
        Route.HOME -> HomeScreen(container, account)
        Route.HISTORY -> HistoryScreen(container, account)
        Route.WORKOUT -> WorkoutScreen(container)
    }
    // The workout has its own top and bottom, as on the phone.
    if (route != Route.WORKOUT) BottomBar(route, container)
}

/** Home · History, the phone's bottom bar; the current one in the accent, announced as the current page. */
@Composable
private fun BottomBar(current: Route, container: WebContainer) {
    val strings = container.strings
    Nav({ classes("bottom-bar") }) {
        listOf(Route.HOME to strings.home, Route.HISTORY to strings.history).forEach { (route, label) ->
            A(href = route.hash, attrs = {
                classes("tab")
                if (route == current) {
                    classes("current")
                    attr("aria-current", "page")
                }
            }) { Text(label) }
        }
    }
}
