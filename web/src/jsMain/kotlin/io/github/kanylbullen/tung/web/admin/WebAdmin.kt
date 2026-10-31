package io.github.kanylbullen.tung.web.admin

import io.github.kanylbullen.tung.web.sync.HttpReply
import io.github.kanylbullen.tung.web.sync.HttpUnreachable
import io.github.kanylbullen.tung.web.sync.deleteRequest
import io.github.kanylbullen.tung.web.sync.getJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val ME_PATH = "/api/v1/me"
const val ADMIN_USERS_PATH = "/api/v1/admin/users"
const val TESTERS_PATH = "/api/v1/testers"

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

/** One address left on the landing page to be invited to the Play test. */
@Serializable
data class TesterSignup(
    val email: String,
    @SerialName("created_at") val createdAt: Long,
    val language: String? = null,
)

@Serializable
private data class TesterList(val testers: List<TesterSignup>)

@Serializable
private data class Me(@SerialName("is_admin") val isAdmin: Boolean = false)

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
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /** True when [token]'s account is an admin of [baseUrl]; false for anyone else, an older server, or no answer. */
    suspend fun isAdmin(baseUrl: String, token: String): Boolean =
        (read(baseUrl + ME_PATH, token, Me.serializer()) as? AdminResult.Ok)?.value?.isAdmin == true

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
