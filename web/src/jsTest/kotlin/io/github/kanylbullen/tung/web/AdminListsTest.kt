package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.web.admin.AdminTab
import io.github.kanylbullen.tung.web.admin.AdminUser
import io.github.kanylbullen.tung.web.admin.BulkOutcome
import io.github.kanylbullen.tung.web.admin.BulkStep
import io.github.kanylbullen.tung.web.admin.FeedbackItem
import io.github.kanylbullen.tung.web.admin.FeedbackKind
import io.github.kanylbullen.tung.web.admin.FeedbackStatus
import io.github.kanylbullen.tung.web.admin.UserFilter
import io.github.kanylbullen.tung.web.admin.UserSort
import io.github.kanylbullen.tung.web.admin.WebAdmin
import io.github.kanylbullen.tung.web.admin.bulkDeleteConfirmed
import io.github.kanylbullen.tung.web.admin.chosenOnShow
import io.github.kanylbullen.tung.web.admin.filterFeedback
import io.github.kanylbullen.tung.web.admin.filterUsers
import io.github.kanylbullen.tung.web.admin.runBulk
import io.github.kanylbullen.tung.web.admin.selectable
import io.github.kanylbullen.tung.web.sync.HttpReply
import io.github.kanylbullen.tung.web.ui.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Server admin's tabs, filters, sorting, selection and bulk actions. */
class AdminListsTest {

    private fun feedback(id: String, kind: String = "bug", message: String = "", email: String = "a@x.org", resolved: Boolean = false) =
        FeedbackItem(id, kind, message, "web", null, createdAt = 0, resolvedAt = if (resolved) 1 else null, email = email)

    private fun user(
        id: String,
        createdAt: Long = 0,
        lastSeenAt: Long? = null,
        workouts: Int = 0,
        isAdmin: Boolean = false,
        emailVerified: Boolean = true,
        email: String = "$id@x.org",
    ) = AdminUser(
        id = id, email = email, createdAt = createdAt, hasPassword = true, googleLinked = false,
        emailVerified = emailVerified, isAdmin = isAdmin, lastSeenAt = lastSeenAt, workouts = workouts,
    )

    @Test
    fun eachTabHasItsHashAndTheBareAdminHashIsFeedback() {
        assertEquals(AdminTab.FEEDBACK, AdminTab.of("#/settings/admin"))
        assertEquals(AdminTab.FEEDBACK, AdminTab.of("#/settings/admin/feedback"))
        assertEquals(AdminTab.USERS, AdminTab.of("#/settings/admin/users"))
        assertEquals(AdminTab.TESTERS, AdminTab.of("#/settings/admin/testers"))
        assertEquals(AdminTab.FEEDBACK, AdminTab.of("#/settings/admin/nonsense"))
        assertEquals("#/settings/admin/users", AdminTab.USERS.hash)
        // Every tab is Server admin, not Settings, whose hash is a prefix of theirs.
        AdminTab.entries.forEach { assertEquals(Route.ADMIN, Route.of(it.hash), it.hash) }
        assertEquals(Route.SETTINGS, Route.of("#/settings"))
        assertEquals(Route.FEEDBACK, Route.of("#/settings/feedback"))
        assertEquals(Route.HISTORY, Route.of("#/history/abc"))
    }

    @Test
    fun feedbackIsFilteredByStateKindAndWords() {
        val items = listOf(
            feedback("1", "bug", "Crash på bänkpress", email = "Anna@x.org"),
            feedback("2", "idea", "Dark mode", resolved = true),
            feedback("3", "idea", "More charts"),
        )
        fun ids(status: FeedbackStatus, kind: FeedbackKind = FeedbackKind.ALL, query: String = "") =
            filterFeedback(items, status, kind, query).map { it.id }
        assertEquals(listOf("1", "3"), ids(FeedbackStatus.OPEN))
        assertEquals(listOf("2"), ids(FeedbackStatus.RESOLVED))
        assertEquals(listOf("1", "2", "3"), ids(FeedbackStatus.ALL))
        assertEquals(listOf("1"), ids(FeedbackStatus.ALL, FeedbackKind.BUG))
        assertEquals(listOf("2", "3"), ids(FeedbackStatus.ALL, FeedbackKind.IDEA))
        assertEquals(listOf("1"), ids(FeedbackStatus.ALL, query = "BANKPRESS"), "case and accents do not matter")
        assertEquals(listOf("1"), ids(FeedbackStatus.ALL, query = "anna"), "the sender's email is searched too")
        assertEquals(emptyList(), ids(FeedbackStatus.RESOLVED, query = "charts"))
    }

    @Test
    fun accountsAreSearchedFilteredAndSorted() {
        val users = listOf(
            user("new", createdAt = 30, workouts = 0, emailVerified = false),
            user("mid", createdAt = 20, lastSeenAt = 5, workouts = 9),
            user("old", createdAt = 10, lastSeenAt = 50, workouts = 2, email = "Old.Timer@x.org"),
        )
        fun ids(query: String = "", filter: UserFilter = UserFilter.ALL, sort: UserSort = UserSort.NEWEST) =
            filterUsers(users, query, filter, sort).map { it.id }
        assertEquals(listOf("new", "mid", "old"), ids())
        assertEquals(listOf("old", "mid", "new"), ids(sort = UserSort.LAST_SEEN), "never seen last")
        assertEquals(listOf("mid", "old", "new"), ids(sort = UserSort.WORKOUTS))
        assertEquals(listOf("new"), ids(filter = UserFilter.NEVER_SEEN))
        assertEquals(listOf("new"), ids(filter = UserFilter.NO_WORKOUTS))
        assertEquals(listOf("new"), ids(filter = UserFilter.UNCONFIRMED))
        assertEquals(listOf("old"), ids(query = " old.timer "))
    }

    @Test
    fun anAdminIsNeverSelectableAndOnlyTickedRowsOnShowAreActedOn() {
        assertFalse(selectable(user("me", isAdmin = true)))
        assertTrue(selectable(user("you")))
        val shown = listOf(user("a"), user("b"), user("c"))
        assertEquals(listOf("a", "c"), chosenOnShow(shown, setOf("c", "a", "hidden", "gone")) { it.id }.map { it.id })
    }

    @Test
    fun deletingManyIsConfirmedByTypingTheCount() {
        assertTrue(bulkDeleteConfirmed(" 7 ", 7))
        assertFalse(bulkDeleteConfirmed("", 7))
        assertFalse(bulkDeleteConfirmed("6", 7))
        assertFalse(bulkDeleteConfirmed("07", 7))
        assertFalse(bulkDeleteConfirmed("0", 0), "nothing to delete is never confirmed")
    }

    @Test
    fun aBulkActionGoesOneAtATimeAndStopsWhenTheSessionCannotGoOn() = runTest {
        val seen = mutableListOf<Int>()
        val progress = mutableListOf<Int>()
        val outcome = runBulk(listOf(1, 2, 3, 4), { progress += it }) { n ->
            seen += n
            when (n) {
                2 -> BulkStep.FAILED
                3 -> BulkStep.STOP
                else -> BulkStep.DONE
            }
        }
        assertEquals(listOf(1, 2, 3), seen, "nothing is asked after a stop")
        assertEquals(listOf(1, 2), progress)
        assertEquals(BulkOutcome(done = 1, failed = 3, stopped = true), outcome)
        assertEquals(BulkOutcome(2, 0), runBulk(listOf("a", "b")) { BulkStep.DONE })
    }

    @Test
    fun deletingManyAccountsSendsEachItsOwnEmailAndNeverAnAdmin() = runTest {
        val asked = mutableListOf<Pair<String, String>>()
        val admin = WebAdmin(deleteWithBody = { url, body, _ ->
            asked += url to body
            if (url.endsWith("/gone")) HttpReply(404, """{"error":{"code":"not_found"}}""") else HttpReply(204, "")
        })
        val outcome = admin.deleteUsers(
            "https://s", "t",
            listOf(user("a"), user("boss", isAdmin = true), user("gone")),
        )
        assertEquals(
            listOf(
                "https://s/api/v1/admin/users/a" to """{"email":"a@x.org"}""",
                "https://s/api/v1/admin/users/gone" to """{"email":"gone@x.org"}""",
            ),
            asked,
            "the admin is skipped without a request",
        )
        assertEquals(BulkOutcome(done = 2, failed = 1), outcome, "gone already counts as done; the admin as failed")

        var calls = 0
        val signedOut = WebAdmin(deleteWithBody = { _, _, _ -> calls++; HttpReply(401, "") })
        assertEquals(BulkOutcome(0, 3, stopped = true), signedOut.deleteUsers("https://s", "t", listOf(user("a"), user("b"), user("c"))))
        assertEquals(1, calls, "a signed-out session stops at the first")
    }

    @Test
    fun feedbackIsResolvedAndDeletedOneRequestEach() = runTest {
        val patched = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val admin = WebAdmin(
            patch = { url, body, _ -> patched += "$url $body"; HttpReply(if (url.endsWith("/2")) 500 else 200, "") },
            delete = { url, _ -> deleted += url; HttpReply(204, "") },
        )
        assertEquals(BulkOutcome(1, 1), admin.setResolved("https://s", "t", listOf("1", "2"), resolved = true))
        assertEquals(
            listOf("https://s/api/v1/admin/feedback/1 {\"resolved\":true}", "https://s/api/v1/admin/feedback/2 {\"resolved\":true}"),
            patched,
        )
        assertEquals(BulkOutcome(2, 0), admin.deleteFeedback("https://s", "t", listOf("1", "3")))
        assertEquals(listOf("https://s/api/v1/admin/feedback/1", "https://s/api/v1/admin/feedback/3"), deleted)
    }
}
