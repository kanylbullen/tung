package io.github.kanylbullen.tung.web.admin

import io.github.kanylbullen.tung.web.sync.HttpReply
import io.github.kanylbullen.tung.web.sync.HttpUnreachable
import io.github.kanylbullen.tung.web.sync.PostJson
import io.github.kanylbullen.tung.web.sync.deleteJson
import io.github.kanylbullen.tung.web.sync.deleteRequest
import io.github.kanylbullen.tung.web.sync.fetchObjectUrl
import io.github.kanylbullen.tung.web.sync.patchJson
import io.github.kanylbullen.tung.web.sync.getJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val ME_PATH = "/api/v1/me"
const val ADMIN_USERS_PATH = "/api/v1/admin/users"
const val TESTERS_PATH = "/api/v1/testers"
const val ADMIN_FEEDBACK_PATH = "/api/v1/admin/feedback"

/** A request with a token: the method's URL and headers, so a test hands in its own. */
typealias AuthedRequest = suspend (url: String, headers: Map<String, String>) -> HttpReply

/** One account as the server's admin sees it, `GET /api/v1/admin/users`. */
@Serializable
data class AdminUser(
    val id: String,
    val email: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("has_password") val hasPassword: Boolean,
    @SerialName("google_linked") val googleLinked: Boolean,
    @SerialName("apple_linked") val appleLinked: Boolean = false,
    /** False while the confirmation link is not opened; true from a server that leaves it out. */
    @SerialName("email_verified") val emailVerified: Boolean = true,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("last_seen_at") val lastSeenAt: Long? = null,
    @SerialName("active_tokens") val activeTokens: Int = 0,
    val workouts: Int = 0,
    @SerialName("last_workout_at") val lastWorkoutAt: Long? = null,
)

@Serializable
data class AdminUsers(
    @SerialName("user_count") val userCount: Int,
    val users: List<AdminUser>,
    /** The server takes test sign-ups (`TUNG_TESTER_SIGNUPS`): only tung.xuper.fun, which runs the Play test. */
    @SerialName("tester_signups") val testerSignups: Boolean = false,
)

/**
 * Every address to invite to the Play test, as the Play Console's list
 * takes them: the test sign-ups first, then every account (the welcome email
 * tells them), each address once whatever its case.
 */
fun playAddresses(testers: List<TesterSignup>, users: List<AdminUser>): String =
    (testers.map { it.email } + users.map { it.email })
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(", ")

/** One address signed up to be invited to the Play test. */
@Serializable
data class TesterSignup(
    val email: String,
    @SerialName("created_at") val createdAt: Long,
    val language: String? = null,
)

/** One screenshot of a feedback, fetched with the token ([WebAdmin.imageUrl]). */
@Serializable
data class FeedbackImage(val id: String, @SerialName("content_type") val contentType: String, val size: Int = 0)

/** One feedback as the admins see it, `GET /api/v1/admin/feedback`. */
@Serializable
data class FeedbackItem(
    val id: String,
    val kind: String,
    val message: String,
    val platform: String,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("resolved_at") val resolvedAt: Long? = null,
    val email: String,
    val images: List<FeedbackImage> = emptyList(),
)

@Serializable
data class FeedbackList(val feedback: List<FeedbackItem>, @SerialName("open_count") val openCount: Int = 0)

@Serializable
private data class ResolvedBody(val resolved: Boolean)

@Serializable
private data class TesterList(val testers: List<TesterSignup>)

/** What Settings reads from `/me`: whether to show Server admin and "Change password…". */
@Serializable
data class AccountFlags(
    @SerialName("is_admin") val isAdmin: Boolean = false,
    /** True from a server that leaves the flag out, as before it existed. */
    @SerialName("has_password") val hasPassword: Boolean = true,
)

/** What `DELETE /api/v1/admin/users/{id}` answered. */
enum class AdminDeleteResult {
    DELETED,

    /** 400 `email_mismatch`: the typed email is not the account's. */
    EMAIL_MISMATCH,

    /** 409 `cannot_delete_admin`: an admin's account, the caller's own included. */
    ADMIN_ACCOUNT,

    /** 403 `session_required`: only a sign-in session may. */
    SESSION_REQUIRED,

    /** 403 otherwise: not an admin (any more). */
    NOT_ADMIN,

    /** 404: deleted already, from another tab or another admin. */
    GONE,

    /** 401: the session has ended. */
    SIGNED_OUT,

    /** No connection, or an answer the API does not give. */
    FAILED,
}

@Serializable
private data class ConfirmEmail(val email: String)

@Serializable
private data class ErrorBody(val error: ErrorDetail? = null)

@Serializable
private data class ErrorDetail(val code: String? = null)

/** What an admin read gave: the value, or why not. */
sealed interface AdminResult<out T> {
    data class Ok<T>(val value: T) : AdminResult<T>

    /** 403: this account is not one of the server's admins (any more). */
    data object NotAdmin : AdminResult<Nothing>

    /** 401: the session has ended. */
    data object SignedOut : AdminResult<Nothing>

    /** No connection, or an answer the API does not give. */
    data object Failed : AdminResult<Nothing>
}

/**
 * The server-admin reads behind Settings → Server admin: whether this
 * account is an admin (`/me`'s `is_admin`), every account, and the Play test
 * sign-ups where the server takes them. Stores nothing; the screen holds
 * what it read.
 */
class WebAdmin(
    private val get: AuthedRequest = { url, headers -> getJson(url, headers) },
    private val delete: AuthedRequest = { url, headers -> deleteRequest(url, headers) },
    private val patch: PostJson = { url, body, headers -> patchJson(url, body, headers) },
    private val deleteWithBody: PostJson = { url, body, headers -> deleteJson(url, body, headers) },
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /** [token]'s account's flags from `/me`, one read for all of Settings; null when there is no answer. */
    suspend fun account(baseUrl: String, token: String): AccountFlags? =
        (read(baseUrl + ME_PATH, token, AccountFlags.serializer()) as? AdminResult.Ok)?.value

    /** True when [token]'s account is an admin of [baseUrl]; false for anyone else, an older server, or no answer. */
    suspend fun isAdmin(baseUrl: String, token: String): Boolean = account(baseUrl, token)?.isAdmin == true

    suspend fun users(baseUrl: String, token: String): AdminResult<AdminUsers> =
        read(baseUrl + ADMIN_USERS_PATH, token, AdminUsers.serializer())

    suspend fun testers(baseUrl: String, token: String): AdminResult<List<TesterSignup>> =
        when (val result = read(baseUrl + TESTERS_PATH, token, TesterList.serializer())) {
            is AdminResult.Ok -> AdminResult.Ok(result.value.testers)
            AdminResult.NotAdmin -> AdminResult.NotAdmin
            AdminResult.SignedOut -> AdminResult.SignedOut
            AdminResult.Failed -> AdminResult.Failed
        }

    /** `DELETE /api/v1/testers/{email}`, once the address is invited; true when the server took it. */
    suspend fun removeTester(baseUrl: String, token: String, email: String): Boolean = try {
        delete(baseUrl + TESTERS_PATH + "/" + encodeURIComponent(email), bearer(token)).isSuccess
    } catch (e: HttpUnreachable) {
        false
    }

    /**
     * `DELETE /api/v1/admin/users/{id}` with [email] typed to confirm: the
     * account and everything in it, as its owner deleting it would.
     */
    suspend fun deleteUser(baseUrl: String, token: String, userId: String, email: String): AdminDeleteResult {
        val reply = try {
            deleteWithBody(
                baseUrl + ADMIN_USERS_PATH + "/" + encodeURIComponent(userId),
                json.encodeToString(ConfirmEmail.serializer(), ConfirmEmail(email.trim())),
                bearer(token),
            )
        } catch (e: HttpUnreachable) {
            return AdminDeleteResult.FAILED
        }
        if (reply.isSuccess) return AdminDeleteResult.DELETED
        val code = try {
            json.decodeFromString(ErrorBody.serializer(), reply.body).error?.code
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return when {
            reply.status == 401 -> AdminDeleteResult.SIGNED_OUT
            code == "email_mismatch" -> AdminDeleteResult.EMAIL_MISMATCH
            code == "cannot_delete_admin" -> AdminDeleteResult.ADMIN_ACCOUNT
            code == "session_required" -> AdminDeleteResult.SESSION_REQUIRED
            reply.status == 403 -> AdminDeleteResult.NOT_ADMIN
            reply.status == 404 -> AdminDeleteResult.GONE
            else -> AdminDeleteResult.FAILED
        }
    }

    /**
     * Deletes [users] one at a time, each with its own email as the
     * confirmation, as [deleteUser] does. An admin's account is skipped and
     * counted as failed without a request, whatever the caller picked;
     * the server refuses it too.
     */
    suspend fun deleteUsers(baseUrl: String, token: String, users: List<AdminUser>, onProgress: (Int) -> Unit = {}): BulkOutcome =
        runBulk(users, onProgress) { user ->
            if (!selectable(user)) BulkStep.FAILED else deleteUser(baseUrl, token, user.id, user.email).asStep()
        }

    /** Marks each of [ids] resolved, or open again, one request each. */
    suspend fun setResolved(baseUrl: String, token: String, ids: List<String>, resolved: Boolean, onProgress: (Int) -> Unit = {}): BulkOutcome =
        runBulk(ids, onProgress) { id -> if (setResolved(baseUrl, token, id, resolved)) BulkStep.DONE else BulkStep.FAILED }

    /** Deletes each of [ids] and its screenshots, one request each. */
    suspend fun deleteFeedback(baseUrl: String, token: String, ids: List<String>, onProgress: (Int) -> Unit = {}): BulkOutcome =
        runBulk(ids, onProgress) { id -> if (deleteFeedback(baseUrl, token, id)) BulkStep.DONE else BulkStep.FAILED }

    suspend fun feedback(baseUrl: String, token: String): AdminResult<FeedbackList> =
        read(baseUrl + ADMIN_FEEDBACK_PATH, token, FeedbackList.serializer())

    /** Marks feedback resolved, or open again; true when the server took it. */
    suspend fun setResolved(baseUrl: String, token: String, feedbackId: String, resolved: Boolean): Boolean = try {
        patch(
            baseUrl + ADMIN_FEEDBACK_PATH + "/" + encodeURIComponent(feedbackId),
            json.encodeToString(ResolvedBody.serializer(), ResolvedBody(resolved)),
            bearer(token),
        ).isSuccess
    } catch (e: HttpUnreachable) {
        false
    }

    /** Deletes feedback and its screenshots; true when the server took it. */
    suspend fun deleteFeedback(baseUrl: String, token: String, feedbackId: String): Boolean = try {
        delete(baseUrl + ADMIN_FEEDBACK_PATH + "/" + encodeURIComponent(feedbackId), bearer(token)).isSuccess
    } catch (e: HttpUnreachable) {
        false
    }

    /** A screenshot as an object URL for an `<img>`, or null; the caller revokes it. */
    suspend fun imageUrl(baseUrl: String, token: String, feedbackId: String, imageId: String): String? = try {
        fetchObjectUrl(
            baseUrl + ADMIN_FEEDBACK_PATH + "/" + encodeURIComponent(feedbackId) + "/images/" + encodeURIComponent(imageId),
            bearer(token),
            SCREENSHOT_TYPES,
        )
    } catch (e: HttpUnreachable) {
        null
    }

    private suspend fun <T> read(url: String, token: String, serializer: KSerializer<T>): AdminResult<T> {
        val reply = try {
            get(url, bearer(token))
        } catch (e: HttpUnreachable) {
            return AdminResult.Failed
        }
        return when {
            reply.status == 401 -> AdminResult.SignedOut
            reply.status == 403 -> AdminResult.NotAdmin
            !reply.isSuccess -> AdminResult.Failed
            else -> try {
                AdminResult.Ok(json.decodeFromString(serializer, reply.body))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AdminResult.Failed
            }
        }
    }

    private fun bearer(token: String) = mapOf("Authorization" to "Bearer $token")
}

private external fun encodeURIComponent(value: String): String

/** What the server stores a screenshot as (its `imageType`, checked by the bytes). */
private val SCREENSHOT_TYPES = setOf("image/png", "image/jpeg", "image/webp")
