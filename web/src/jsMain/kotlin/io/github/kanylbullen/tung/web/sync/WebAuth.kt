package io.github.kanylbullen.tung.web.sync

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val LOGIN_PATH = "/api/v1/auth/login"
const val LOGOUT_PATH = "/api/v1/auth/logout"

/** Why a sign-in failed, as the phone's `AuthFailure` names it. */
enum class AuthFailure {
    /** 401 on login. */
    INVALID_CREDENTIALS,

    /** 400: most likely the email. */
    INVALID_REQUEST,

    /** 429. */
    RATE_LIMITED,

    /** 404 or 405: something answers, but not tung-server's API. */
    NOT_A_SERVER,

    /** No connection, refused, blocked. */
    NETWORK,

    /** 5xx, or an answer that is not what the API returns. */
    SERVER,
}

sealed interface AuthResult {
    data class Success(val token: String, val email: String) : AuthResult
    data class Failed(val failure: AuthFailure) : AuthResult
}

/** Sign in and out against tung-server over `fetch`. Stores nothing; the container does. */
class WebAuth(private val json: Json = AuthJson) {

    /** `POST /api/v1/auth/login` at [baseUrl]. */
    suspend fun signIn(baseUrl: String, email: String, password: String): AuthResult {
        val reply = try {
            postJson(baseUrl + LOGIN_PATH, json.encodeToString(Credentials.serializer(), Credentials(email, password)))
        } catch (e: HttpUnreachable) {
            return AuthResult.Failed(AuthFailure.NETWORK)
        }
        if (!reply.isSuccess) return AuthResult.Failed(failureOf(reply.status))
        val session = try {
            json.decodeFromString(Session.serializer(), reply.body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return AuthResult.Failed(AuthFailure.SERVER)
        }
        return AuthResult.Success(session.token, session.user?.email ?: email)
    }

    /** `POST /api/v1/auth/logout`: the server forgets the token. Best effort; the browser forgets it either way. */
    suspend fun signOut(baseUrl: String, token: String) {
        try {
            postJson(baseUrl + LOGOUT_PATH, "{}", mapOf("Authorization" to "Bearer $token"))
        } catch (e: HttpUnreachable) {
            // Offline: the token expires on the server by itself.
        }
    }

    private fun failureOf(status: Int): AuthFailure = when (status) {
        401 -> AuthFailure.INVALID_CREDENTIALS
        429 -> AuthFailure.RATE_LIMITED
        404, 405 -> AuthFailure.NOT_A_SERVER
        in 400..499 -> AuthFailure.INVALID_REQUEST
        else -> AuthFailure.SERVER
    }

    @Serializable
    private data class Credentials(val email: String, val password: String)

    @Serializable
    private data class Session(val token: String, val user: SessionUser? = null)

    @Serializable
    private data class SessionUser(val email: String)

    companion object {
        val AuthJson: Json = Json { ignoreUnknownKeys = true }
    }
}
