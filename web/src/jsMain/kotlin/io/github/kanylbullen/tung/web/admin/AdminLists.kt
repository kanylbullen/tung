package io.github.kanylbullen.tung.web.admin

import io.github.kanylbullen.tung.core.command.normalizeExerciseName

/** Server admin's tabs, each its own hash under `#/settings/admin`, so back and forward move between them. */
enum class AdminTab(val slug: String) {
    FEEDBACK("feedback"),
    USERS("users"),
    TESTERS("testers"),
    ;

    val hash: String get() = "$ADMIN_HASH/$slug"

    companion object {
        /** The tab a hash names; `#/settings/admin` itself, and anything unknown, is Feedback. */
        fun of(hash: String): AdminTab =
            entries.firstOrNull { hash == it.hash || hash.startsWith(it.hash + "/") } ?: FEEDBACK
    }
}

const val ADMIN_HASH = "#/settings/admin"

/** Which feedback the list shows by its state. Open by default: that is what is left to do. */
enum class FeedbackStatus { OPEN, RESOLVED, ALL }

/** Which feedback the list shows by its kind. */
enum class FeedbackKind { ALL, BUG, IDEA }

/**
 * The feedback that passes the filters, in the server's order (newest
 * first): its state, its kind, and [query] found in its words or its
 * sender's email, whatever the case and the accents.
 */
fun filterFeedback(items: List<FeedbackItem>, status: FeedbackStatus, kind: FeedbackKind, query: String): List<FeedbackItem> {
    val needle = fold(query)
    return items.filter { item ->
        val statusOk = when (status) {
            FeedbackStatus.OPEN -> item.resolvedAt == null
            FeedbackStatus.RESOLVED -> item.resolvedAt != null
            FeedbackStatus.ALL -> true
        }
        val kindOk = when (kind) {
            FeedbackKind.ALL -> true
            FeedbackKind.BUG -> item.kind == "bug"
            FeedbackKind.IDEA -> item.kind != "bug"
        }
        statusOk && kindOk && (needle.isEmpty() || needle in fold(item.message) || needle in fold(item.email))
    }
}

/** How the account list is ordered. */
enum class UserSort {
    /** Newest account first, as the server sends them. */
    NEWEST,

    /** Most recently seen first; never seen last. */
    LAST_SEEN,

    /** Most finished workouts first. */
    WORKOUTS,
}

/** Which accounts the list shows. */
enum class UserFilter {
    ALL,

    /** No token of theirs was ever used. */
    NEVER_SEEN,

    /** No finished workout. */
    NO_WORKOUTS,

    /** The confirmation link was never opened. */
    UNCONFIRMED,
}

/** The accounts that pass [filter] and contain [query] in their email, in [sort]'s order; ties keep the server's order. */
fun filterUsers(users: List<AdminUser>, query: String, filter: UserFilter, sort: UserSort): List<AdminUser> {
    val needle = query.trim().lowercase()
    val shown = users.filter { user ->
        val filterOk = when (filter) {
            UserFilter.ALL -> true
            UserFilter.NEVER_SEEN -> user.lastSeenAt == null
            UserFilter.NO_WORKOUTS -> user.workouts == 0
            UserFilter.UNCONFIRMED -> !user.emailVerified
        }
        filterOk && (needle.isEmpty() || needle in user.email.lowercase())
    }
    return when (sort) {
        UserSort.NEWEST -> shown.sortedByDescending { it.createdAt }
        UserSort.LAST_SEEN -> shown.sortedByDescending { it.lastSeenAt ?: Long.MIN_VALUE }
        UserSort.WORKOUTS -> shown.sortedByDescending { it.workouts }
    }
}

/** Whether an account may be picked for deletion: never an admin's, the caller's own included (every admin is one). */
fun selectable(user: AdminUser): Boolean = !user.isAdmin

/**
 * What a bulk action acts on: the chosen ids among the rows on show, in
 * their order. A row hidden by a filter or gone since the last read is never
 * acted on, whatever was ticked before.
 */
fun <T> chosenOnShow(shown: List<T>, chosen: Set<String>, id: (T) -> String): List<T> = shown.filter { id(it) in chosen }

/** The deletion of N accounts is confirmed by typing N, so a stray tap or Enter cannot do it. */
fun bulkDeleteConfirmed(typed: String, count: Int): Boolean = count > 0 && typed.trim() == count.toString()

/** How a bulk action went: how many the server took, how many it refused or never got, and whether it stopped early. */
data class BulkOutcome(val done: Int, val failed: Int, val stopped: Boolean = false) {
    val total: Int get() = done + failed
}

/** What one step of a bulk action gave. */
enum class BulkStep {
    DONE,
    FAILED,

    /** The session ended or the account is no longer an admin: the rest would fail the same way. */
    STOP,
}

/**
 * Runs [action] on each of [items], one at a time (each is its own request
 * and its own transaction on the server), reporting progress after each.
 * A [BulkStep.STOP] ends it, counting the rest as failed.
 */
suspend fun <T> runBulk(items: List<T>, onProgress: (Int) -> Unit = {}, action: suspend (T) -> BulkStep): BulkOutcome {
    var done = 0
    var failed = 0
    for ((index, item) in items.withIndex()) {
        when (action(item)) {
            BulkStep.DONE -> done++
            BulkStep.FAILED -> failed++
            BulkStep.STOP -> return BulkOutcome(done, items.size - done, stopped = true)
        }
        onProgress(index + 1)
    }
    return BulkOutcome(done, failed)
}

/** One account's deletion as a step: gone already counts as done, a session that cannot go on stops the rest. */
fun AdminDeleteResult.asStep(): BulkStep = when (this) {
    AdminDeleteResult.DELETED, AdminDeleteResult.GONE -> BulkStep.DONE
    AdminDeleteResult.SIGNED_OUT, AdminDeleteResult.NOT_ADMIN, AdminDeleteResult.SESSION_REQUIRED -> BulkStep.STOP
    AdminDeleteResult.EMAIL_MISMATCH, AdminDeleteResult.ADMIN_ACCOUNT, AdminDeleteResult.FAILED -> BulkStep.FAILED
}

/** Lower case without accents or extra spaces, so "Bänk" finds "bank" and "BÄNK". */
private fun fold(text: String): String = normalizeExerciseName(text)
