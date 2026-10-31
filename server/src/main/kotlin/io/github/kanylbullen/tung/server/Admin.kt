package io.github.kanylbullen.tung.server

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.selectAll
import java.sql.Connection

/** The most accounts one admin listing carries, newest first; [AdminUsers.userCount] is the true total. */
const val MAX_ADMIN_USERS = 1_000

/**
 * The server's admins: the accounts whose email is in `TUNG_ADMIN_EMAILS`.
 * The one place that decides it, for `/me`'s `is_admin`, the account list
 * and the test sign-ups.
 */
class Admins(private val db: Db, private val emails: Set<String>) {

    suspend fun isAdmin(userId: String): Boolean {
        if (emails.isEmpty()) return false
        val email = db.tx { Users.selectAll().where { Users.id eq userId }.singleOrNull()?.get(Users.email) }
        return email != null && isAdminEmail(email)
    }

    fun isAdminEmail(email: String): Boolean = email.lowercase() in emails

    /**
     * Only a write token: a `read` token is what an admin hands an assistant
     * to look at their own training, and it must not carry every account's
     * email with it.
     */
    suspend fun require(principal: TokenPrincipal) {
        if (principal.scope == "read") {
            throw forbidden("insufficient_scope", "A read-only token cannot use the admin routes. Sign in to use them.")
        }
        if (!isAdmin(principal.userId)) {
            throw forbidden("not_admin", "Only the server's admins (TUNG_ADMIN_EMAILS) may see this.")
        }
    }
}

/** One account as an admin sees it: who, how they sign in, and whether they use the server. No training data. */
@Serializable
data class AdminUserDto(
    val id: String,
    val email: String,
    val createdAt: Long,
    val hasPassword: Boolean,
    val googleLinked: Boolean,
    val isAdmin: Boolean,
    /** The latest use of any of the account's tokens, null when none was ever used. */
    val lastSeenAt: Long?,
    /** Tokens that still work: sessions, devices, assistants. */
    val activeTokens: Int,
    /** Finished workouts that are not deleted. */
    val workouts: Int,
    /** When the latest of them started. */
    val lastWorkoutAt: Long?,
)

@Serializable
data class AdminUsers(
    val userCount: Int,
    val users: List<AdminUserDto>,
    /** `TUNG_TESTER_SIGNUPS` is on: `/api/v1/testers` has a list to show. */
    val testerSignups: Boolean,
)

/** What `GET /api/v1/admin/users` reads: every account with a few counts, in one query. */
class AdminStore(private val db: Db, private val admins: Admins, private val testerSignups: Boolean, private val clock: () -> Long) {

    suspend fun users(principal: TokenPrincipal): AdminUsers {
        admins.require(principal)
        val now = clock()
        return db.tx {
            val connection = connection.connection as Connection
            val total = connection.prepareStatement("SELECT count(*) FROM users").use { statement ->
                statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
            val users = connection.prepareStatement(USERS_SQL).use { statement ->
                statement.setLong(1, now)
                statement.setInt(2, MAX_ADMIN_USERS)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            val email = rows.getString("email")
                            add(
                                AdminUserDto(
                                    id = rows.getString("id"),
                                    email = email,
                                    createdAt = rows.getLong("created_at"),
                                    hasPassword = rows.getBoolean("has_password"),
                                    googleLinked = rows.getBoolean("google_linked"),
                                    isAdmin = admins.isAdminEmail(email),
                                    lastSeenAt = rows.getLong("last_seen_at").takeUnless { rows.wasNull() },
                                    activeTokens = rows.getInt("active_tokens"),
                                    workouts = rows.getInt("workouts"),
                                    lastWorkoutAt = rows.getLong("last_workout_at").takeUnless { rows.wasNull() },
                                ),
                            )
                        }
                    }
                }
            }
            AdminUsers(total, users, testerSignups)
        }
    }

    private companion object {
        val USERS_SQL = """
            SELECT u.id, u.email, u.created_at,
                   u.password_hash IS NOT NULL AS has_password,
                   u.google_sub IS NOT NULL AS google_linked,
                   t.last_seen_at, coalesce(t.active_tokens, 0) AS active_tokens,
                   coalesce(w.workouts, 0) AS workouts, w.last_workout_at
            FROM users u
            LEFT JOIN (
                SELECT user_id, max(last_used_at) AS last_seen_at,
                       count(*) FILTER (WHERE revoked_at IS NULL AND (expires_at IS NULL OR expires_at > ?)) AS active_tokens
                FROM api_tokens GROUP BY user_id
            ) t ON t.user_id = u.id
            LEFT JOIN (
                SELECT user_id, count(*) AS workouts, max(started_at) AS last_workout_at
                FROM workouts WHERE deleted_at IS NULL AND ended_at IS NOT NULL GROUP BY user_id
            ) w ON w.user_id = u.id
            ORDER BY u.created_at DESC, u.id
            LIMIT ?
        """.trimIndent()
    }
}

/** `GET /api/v1/admin/users`, behind the bearer token; admins only. */
fun Route.adminRoutes(store: AdminStore) {
    route("/admin") {
        get("/users") { call.respond(store.users(call.user())) }
    }
}
