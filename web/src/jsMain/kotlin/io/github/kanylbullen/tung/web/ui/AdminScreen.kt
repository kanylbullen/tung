package io.github.kanylbullen.tung.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.kanylbullen.tung.web.WebContainer
import io.github.kanylbullen.tung.web.admin.AdminResult
import io.github.kanylbullen.tung.web.admin.AdminUser
import io.github.kanylbullen.tung.web.admin.AdminUsers
import io.github.kanylbullen.tung.web.admin.TesterSignup
import io.github.kanylbullen.tung.web.sync.Account
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import kotlin.js.Date

/** What the admin screen has read: nothing yet, the lists, or why not. */
private sealed interface AdminView {
    data object Loading : AdminView
    data class Ready(val users: AdminUsers, val testers: List<TesterSignup>?) : AdminView
    data class Failed(val message: String) : AdminView
}

/**
 * Settings → Server admin, for an account in the server's
 * `TUNG_ADMIN_EMAILS`: every account on the server, newest first, with how
 * it signs in, when it was last seen and how many workouts it has, and, on
 * a server that takes them (`TUNG_TESTER_SIGNUPS`, tung.xuper.fun), the
 * addresses left on the front page for the Play test, to copy into the Play
 * Console and remove once invited. A self-hosted server has no such list,
 * so the section is not there.
 */
@Composable
fun AdminScreen(container: WebContainer, account: Account) {
    val strings = container.strings
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<AdminView>(AdminView.Loading) }
    var reload by remember { mutableStateOf(0) }
    var removing by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var removeFailed by remember { mutableStateOf(false) }
    val now = Date.now().toLong()

    LaunchedEffect(account, reload) {
        view = AdminView.Loading
        copied = false
        val token = container.settings.token
        if (token == null) {
            view = AdminView.Failed(strings.adminFailed)
            return@LaunchedEffect
        }
        view = when (val users = container.admin.users(account.serverUrl, token)) {
            is AdminResult.Ok -> {
                val testers = if (!users.value.testerSignups) null else {
                    (container.admin.testers(account.serverUrl, token) as? AdminResult.Ok)?.value
                }
                AdminView.Ready(users.value, testers)
            }
            AdminResult.NotAdmin -> AdminView.Failed(strings.adminNotAdmin)
            AdminResult.SignedOut, AdminResult.Failed -> AdminView.Failed(strings.adminFailed)
        }
    }

    Div({ classes("screen") }) {
        Div({ classes("topbar", "topbar-start") }) {
            A(href = Route.SETTINGS.hash, attrs = { classes("text-button"); attr("aria-label", strings.settings) }) { Text("←") }
            H1({ classes("detail-title") }) { Text(strings.serverAdmin) }
        }

        when (val current = view) {
            AdminView.Loading -> P({ classes("hint") }) { Text(strings.loading) }
            is AdminView.Failed -> {
                P({ classes("error"); attr("role", "alert") }) { Text(current.message) }
                Div({ classes("setting-buttons", "pad") }) {
                    Button({ classes("secondary"); onClick { removeFailed = false; reload++ } }) { Text(strings.tryAgain) }
                }
            }
            is AdminView.Ready -> {
                val testers = current.testers
                if (current.users.testerSignups) {
                    H2({ classes("section") }) { Text(strings.testerSignups) }
                    P({ classes("hint") }) { Text(strings.testerSignupsHint) }
                    when {
                        testers == null -> P({ classes("error") }) { Text(strings.adminFailed) }
                        testers.isEmpty() -> P({ classes("hint") }) { Text(strings.testersEmpty) }
                        else -> {
                            if (removeFailed) P({ classes("error"); attr("role", "alert") }) { Text(strings.adminFailed) }
                            Div({ classes("setting-buttons", "pad") }) {
                                Button({
                                    classes("secondary")
                                    onClick {
                                        scope.launch {
                                            // Comma-separated, as the Play Console's tester list takes them.
                                            runCatching { window.navigator.clipboard.writeText(testers.joinToString(", ") { it.email }).await() }
                                                .onSuccess { copied = true }
                                        }
                                    }
                                }) { Text(if (copied) strings.copied else strings.copyAddresses) }
                            }
                            Ul({ classes("rows") }) {
                                testers.forEach { tester ->
                                    key(tester.email) {
                                        Li({ classes("row") }) {
                                            Div({ classes("main") }) {
                                                Div({ classes("title") }) { Span({ classes("plain") }) { Text(tester.email) } }
                                                Div({ classes("details", "numbers") }) {
                                                    Text(listOfNotNull(shortDate(tester.createdAt, container.locale, now), tester.language?.uppercase()).joinToString(strings.separator))
                                                }
                                            }
                                            Button({
                                                classes("text-button")
                                                attr("aria-label", strings.removeTester(tester.email))
                                                onClick { removeFailed = false; removing = tester.email }
                                            }) { Text(strings.remove) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                H2({ classes("section") }) { Text(strings.accountCount(current.users.userCount)) }
                if (current.users.users.size < current.users.userCount) {
                    P({ classes("hint") }) { Text(strings.accountsShown(current.users.users.size, current.users.userCount)) }
                }
                Ul({ classes("rows") }) {
                    current.users.users.forEach { user ->
                        key(user.id) { Li({ classes("row") }) { UserRow(user, container, now) } }
                    }
                }
            }
        }
    }

    val email = removing
    if (email != null) {
        Dialog(
            title = strings.removeTester(email),
            body = strings.removeTesterBody,
            confirm = strings.remove,
            cancel = strings.cancel,
            onCancel = { removing = null },
            onConfirm = {
                removing = null
                scope.launch {
                    val token = container.settings.token ?: return@launch
                    if (container.admin.removeTester(account.serverUrl, token, email)) {
                        reload++
                    } else {
                        removeFailed = true
                    }
                }
            },
        )
    }
}

/** One account: the email (and "Admin"), when it joined and how it signs in, and whether it is used. */
@Composable
private fun UserRow(user: AdminUser, container: WebContainer, now: Long) {
    val strings = container.strings
    Div({ classes("main") }) {
        Div({ classes("title") }) {
            Span({ classes("plain") }) { Text(user.email) }
        }
        Div({ classes("details", "numbers") }) {
            Text(
                listOfNotNull(
                    strings.joined(shortDate(user.createdAt, container.locale, now)),
                    strings.signInGoogle.takeIf { user.googleLinked },
                    strings.signInPassword.takeIf { user.hasPassword },
                    strings.adminBadge.takeIf { user.isAdmin },
                ).joinToString(strings.separator),
            )
        }
        Div({ classes("details", "numbers") }) {
            val seen = user.lastSeenAt?.let { strings.lastSeen(shortDate(it, container.locale, now)) } ?: strings.neverSeen
            val workouts = strings.weekWorkouts(user.workouts) +
                (user.lastWorkoutAt?.let { ", " + strings.lastWorkout(shortDate(it, container.locale, now)) } ?: "")
            Text(listOf(seen, workouts).joinToString(strings.separator))
        }
    }
}
