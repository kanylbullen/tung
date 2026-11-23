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
import io.github.kanylbullen.tung.web.admin.AdminDeleteResult
import io.github.kanylbullen.tung.web.admin.AdminTab
import io.github.kanylbullen.tung.web.admin.BulkOutcome
import io.github.kanylbullen.tung.web.admin.FeedbackList
import io.github.kanylbullen.tung.web.admin.UserFilter
import io.github.kanylbullen.tung.web.admin.UserSort
import io.github.kanylbullen.tung.web.admin.bulkDeleteConfirmed
import io.github.kanylbullen.tung.web.admin.chosenOnShow
import io.github.kanylbullen.tung.web.admin.filterUsers
import io.github.kanylbullen.tung.web.admin.selectable
import io.github.kanylbullen.tung.web.admin.AdminResult
import io.github.kanylbullen.tung.web.admin.AdminUser
import io.github.kanylbullen.tung.web.admin.AdminUsers
import io.github.kanylbullen.tung.web.admin.TesterSignup
import io.github.kanylbullen.tung.web.admin.playAddresses
import io.github.kanylbullen.tung.web.sync.Account
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.AutoComplete
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.autoComplete
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
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
 * `TUNG_ADMIN_EMAILS`, in tabs by [hash] ([AdminTab]): the feedback users
 * sent ([FeedbackTab]); every account on the server ([UsersTab]); and, on
 * a server that takes them (`TUNG_TESTER_SIGNUPS`, tung.xuper.fun), the
 * addresses signed up for a Play test ([TestersTab]). A self-hosted server
 * has no such list, so that tab is not there. The feedback and the accounts
 * are read separately, so one failing leaves the other.
 */
@Composable
fun AdminScreen(container: WebContainer, account: Account, hash: String) {
    val strings = container.strings
    var view by remember { mutableStateOf<AdminView>(AdminView.Loading) }
    var reload by remember { mutableStateOf(0) }
    var feedback by remember { mutableStateOf<FeedbackList?>(null) }
    var feedbackFailed by remember { mutableStateOf(false) }
    var feedbackReload by remember { mutableStateOf(0) }
    val now = Date.now().toLong()

    LaunchedEffect(account, reload) {
        val token = container.settings.token
        if (token == null) {
            view = AdminView.Failed(strings.adminFailed)
            return@LaunchedEffect
        }
        val next = when (val users = container.admin.users(account.serverUrl, token)) {
            is AdminResult.Ok -> {
                val testers = if (!users.value.testerSignups) null else {
                    (container.admin.testers(account.serverUrl, token) as? AdminResult.Ok)?.value
                }
                AdminView.Ready(users.value, testers)
            }
            AdminResult.NotAdmin -> AdminView.Failed(strings.adminNotAdmin)
            AdminResult.SignedOut, AdminResult.Failed -> AdminView.Failed(strings.adminFailed)
        }
        // A read after a change keeps the old list on screen until the new one is in.
        view = next
    }

    LaunchedEffect(account, feedbackReload) {
        val token = container.settings.token
        if (token == null) {
            feedbackFailed = true
            return@LaunchedEffect
        }
        when (val result = container.admin.feedback(account.serverUrl, token)) {
            is AdminResult.Ok -> { feedback = result.value; feedbackFailed = false }
            else -> feedbackFailed = true
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
                    Button({ classes("secondary"); onClick { view = AdminView.Loading; reload++; feedbackReload++ } }) { Text(strings.tryAgain) }
                }
            }
            is AdminView.Ready -> {
                val asked = AdminTab.of(hash)
                val tab = if (asked == AdminTab.TESTERS && !current.users.testerSignups) AdminTab.FEEDBACK else asked
                val tabs = listOfNotNull(AdminTab.FEEDBACK, AdminTab.USERS, AdminTab.TESTERS.takeIf { current.users.testerSignups })
                Div({ classes("chips"); attr("role", "tablist"); attr("aria-label", strings.adminTabs) }) {
                    tabs.forEach { each ->
                        A(href = each.hash, attrs = {
                            classes("chip")
                            if (each == tab) classes("on")
                            attr("role", "tab")
                            attr("aria-selected", (each == tab).toString())
                        }) {
                            Text(
                                when (each) {
                                    AdminTab.FEEDBACK -> strings.adminTabFeedback(feedback?.openCount ?: 0)
                                    AdminTab.USERS -> strings.adminTabUsers(current.users.userCount)
                                    AdminTab.TESTERS -> strings.testerSignups
                                },
                            )
                        }
                    }
                }
                Div({ attr("role", "tabpanel") }) {
                    when (tab) {
                        AdminTab.FEEDBACK -> FeedbackTab(container, account, feedback, feedbackFailed, now) { feedbackReload++ }
                        AdminTab.USERS -> UsersTab(container, account, current.users, now) { reload++ }
                        AdminTab.TESTERS -> TestersTab(container, account, current.users, current.testers, now) { reload++ }
                    }
                }
            }
        }
    }
}

/**
 * The Play test's sign-ups: every address to copy into the Play Console
 * (the sign-ups and the accounts, for one paste), the sign-ups alone, and
 * each one to remove once invited.
 */
@Composable
private fun TestersTab(container: WebContainer, account: Account, users: AdminUsers, testers: List<TesterSignup>?, now: Long, onChanged: () -> Unit) {
    val strings = container.strings
    val scope = rememberCoroutineScope()
    var removing by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var copiedAll by remember { mutableStateOf(false) }
    var removeFailed by remember { mutableStateOf(false) }

    P({ classes("hint") }) { Text(strings.testerSignupsHint) }
    if (testers != null) {
        P({ classes("hint") }) { Text(strings.playAddressesHint) }
        if (users.users.size < users.userCount) {
            P({ classes("hint") }) { Text(strings.accountsShown(users.users.size, users.userCount)) }
        }
        Div({ classes("setting-buttons", "pad") }) {
            Button({
                classes("secondary")
                onClick {
                    scope.launch {
                        runCatching { window.navigator.clipboard.writeText(playAddresses(testers, users.users)).await() }
                            .onSuccess { copiedAll = true }
                    }
                }
            }) { Text(if (copiedAll) strings.copied else strings.copyPlayAddresses) }
        }
    }
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
                        copied = false
                        onChanged()
                    } else {
                        removeFailed = true
                    }
                }
            },
        )
    }
}

/**
 * Every account, searched by email, filtered and sorted. Any account but an
 * admin's can be ticked and the ticked ones on show deleted together, the
 * count typed to confirm, one request each with its own email
 * ([io.github.kanylbullen.tung.web.admin.WebAdmin.deleteUsers]); one alone
 * is deleted from its row with its email typed ([DeleteUserDialog]).
 */
@Composable
private fun UsersTab(container: WebContainer, account: Account, users: AdminUsers, now: Long, onChanged: () -> Unit) {
    val strings = container.strings
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(UserFilter.ALL) }
    var sort by remember { mutableStateOf(UserSort.NEWEST) }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var deleting by remember { mutableStateOf<AdminUser?>(null) }
    var bulkDeleting by remember { mutableStateOf<List<AdminUser>?>(null) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var outcome by remember { mutableStateOf<BulkOutcome?>(null) }

    if (users.users.size < users.userCount) {
        P({ classes("hint") }) { Text(strings.accountsShown(users.users.size, users.userCount)) }
    }
    AdminSearch(strings.searchAccounts, query) { query = it }
    AdminChips(strings.filterAccounts, UserFilter.entries, filter, {
        when (it) {
            UserFilter.ALL -> strings.filterAll
            UserFilter.NEVER_SEEN -> strings.filterNeverSeen
            UserFilter.NO_WORKOUTS -> strings.filterNoWorkouts
            UserFilter.UNCONFIRMED -> strings.filterUnconfirmed
        }
    }) { filter = it }
    AdminChips(strings.sortBy, UserSort.entries, sort, {
        when (it) {
            UserSort.NEWEST -> strings.sortNewest
            UserSort.LAST_SEEN -> strings.sortLastSeen
            UserSort.WORKOUTS -> strings.sortWorkouts
        }
    }) { sort = it }

    val shown = filterUsers(users.users, query, filter, sort)
    val pickable = shown.filter(::selectable)
    val chosen = chosenOnShow(pickable, selection) { it.id }
    val running = progress
    SelectionBar(container, pickable.size, chosen.size, running, onSelectAll = { selection = pickable.map { it.id }.toSet() }, onClear = { selection = emptySet() }) {
        Button({
            classes("text-button", "danger-text")
            if (running != null) disabled()
            onClick { bulkDeleting = chosen }
        }) { Text(strings.deleteSelectedEllipsis) }
    }
    outcome?.let { P({ classes("error"); attr("role", "alert") }) { Text(strings.bulkFailed(it.done, it.failed)) } }

    if (shown.isEmpty()) {
        P({ classes("hint") }) { Text(strings.nothingMatches) }
    } else {
        Ul({ classes("rows") }) {
            shown.forEach { user ->
                key(user.id) {
                    Li({ classes("row") }) {
                        // An admin's account is never deleted from here, alone or with others; the server refuses it too.
                        if (selectable(user)) {
                            Input(InputType.Checkbox) {
                                classes("row-check")
                                checked(user.id in selection)
                                attr("aria-label", strings.selectRow(user.email))
                                onChange {
                                    val current = selection
                                    selection = if (user.id in current) current - user.id else current + user.id
                                }
                            }
                        } else {
                            Span({ classes("row-check") })
                        }
                        UserRow(user, container, now)
                        if (selectable(user)) {
                            Button({
                                classes("text-button", "danger-text")
                                attr("aria-label", strings.deleteUserTitle(user.email))
                                onClick { deleting = user }
                            }) { Text(strings.deleteUser) }
                        }
                    }
                }
            }
        }
    }

    deleting?.let { user ->
        DeleteUserDialog(container, account, user, onClose = { deleting = null }, onDeleted = { deleting = null; onChanged() })
    }

    bulkDeleting?.let { picked ->
        DeleteUsersDialog(container, picked.size, onCancel = { bulkDeleting = null }) {
            bulkDeleting = null
            val token = container.settings.token ?: return@DeleteUsersDialog
            outcome = null
            progress = 0 to picked.size
            scope.launch {
                val result = container.admin.deleteUsers(account.serverUrl, token, picked) { done -> progress = done to picked.size }
                progress = null
                outcome = result.takeIf { it.failed > 0 }
                if (result.failed == 0) selection = emptySet()
                onChanged()
            }
        }
    }
}

/** "Delete 7 accounts?": what goes, and the number typed to confirm. */
@Composable
private fun DeleteUsersDialog(container: WebContainer, count: Int, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val strings = container.strings
    var typed by remember(count) { mutableStateOf("") }
    val title = strings.deleteUsersTitle(count)
    Div({ classes("sheet-backdrop"); onClick { onCancel() } })
    Div({ classes("dialog"); attr("role", "alertdialog"); attr("aria-modal", "true"); attr("aria-label", title) }) {
        H2 { Text(title) }
        P { Text(strings.deleteUsersBody) }
        Label(attrs = { classes("field", "dialog-field") }) {
            Span { Text(strings.deleteUsersType(count)) }
            Input(InputType.Text) {
                value(typed)
                autoComplete(AutoComplete.off)
                attr("inputmode", "numeric")
                onInput { typed = it.value }
            }
        }
        Div({ classes("dialog-actions") }) {
            Button({ classes("secondary"); onClick { onCancel() } }) { Text(strings.cancel) }
            Button({
                classes("accent-button")
                if (!bulkDeleteConfirmed(typed, count)) disabled()
                onClick { if (bulkDeleteConfirmed(typed, count)) onConfirm() }
            }) { Text(strings.deleteUsersConfirm(count)) }
        }
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
                    strings.signInApple.takeIf { user.appleLinked },
                    strings.signInPassword.takeIf { user.hasPassword },
                    strings.adminBadge.takeIf { user.isAdmin },
                    strings.emailUnconfirmed.takeIf { !user.emailVerified },
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

/**
 * "Delete <email>?": the account's email typed to confirm, as the account's
 * own deletion asks, then `DELETE /api/v1/admin/users/{id}`. Stays open with
 * the server's reason when it refuses.
 */
@Composable
private fun DeleteUserDialog(container: WebContainer, account: Account, user: AdminUser, onClose: () -> Unit, onDeleted: () -> Unit) {
    val strings = container.strings
    val scope = rememberCoroutineScope()
    var typed by remember(user.id) { mutableStateOf("") }
    var busy by remember(user.id) { mutableStateOf(false) }
    var error by remember(user.id) { mutableStateOf<String?>(null) }
    fun close() {
        if (!busy) onClose()
    }
    Div({ classes("sheet-backdrop"); onClick { close() } })
    Div({ classes("dialog"); attr("role", "dialog"); attr("aria-modal", "true"); attr("aria-label", strings.deleteUserTitle(user.email)) }) {
        H2 { Text(strings.deleteUserTitle(user.email)) }
        P { Text(strings.deleteUserBody) }
        Label(attrs = { classes("field", "dialog-field") }) {
            Span { Text(strings.deleteUserEmail) }
            Input(InputType.Email) {
                value(typed)
                autoComplete(AutoComplete.off)
                onInput { typed = it.value; error = null }
                if (busy) disabled()
            }
        }
        error?.let { message -> P({ classes("error"); attr("role", "alert") }) { Text(message) } }
        Div({ classes("dialog-actions") }) {
            Button({ classes("secondary"); if (busy) disabled(); onClick { close() } }) { Text(strings.cancel) }
            Button({
                classes("accent-button")
                if (busy || typed.isBlank()) disabled()
                onClick {
                    busy = true
                    scope.launch {
                        val token = container.settings.token
                        val result = if (token == null) AdminDeleteResult.SIGNED_OUT else {
                            container.admin.deleteUser(account.serverUrl, token, user.id, typed)
                        }
                        busy = false
                        when (result) {
                            AdminDeleteResult.DELETED, AdminDeleteResult.GONE -> onDeleted()
                            else -> error = strings.deleteUserFailure(result)
                        }
                    }
                }
            }) { Text(strings.deleteUserConfirm) }
        }
    }
}
