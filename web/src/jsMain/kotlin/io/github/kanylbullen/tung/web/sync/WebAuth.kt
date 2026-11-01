package io.github.kanylbullen.tung.web.sync

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val LOGIN_PATH = "/api/v1/auth/login"
const val REGISTER_PATH = "/api/v1/auth/register"
const val LOGOUT_PATH = "/api/v1/auth/logout"
const val GOOGLE_PATH = "/api/v1/auth/google"
const val METHODS_PATH = "/api/v1/auth/methods"
const val ACCOUNT_PATH = "/api/v1/me"

/** What deleting the account gave, as the phone's `DeleteAccountOutcome` names it. */
enum class DeleteAccountResult {
    DELETED,

    /** 400 `email_mismatch`: the typed email is not the account's. */
    EMAIL_MISMATCH,

    /** 403 `session_required`: this token is not a sign-in session. */
    NOT_ALLOWED,

    /** 401: the token is no longer valid. */
    SIGNED_OUT,

    /** 404 or 405: a server from before account deletion. */
    NOT_A_SERVER,

    /** No connection, refused, blocked. */
    NETWORK,

    /** 5xx, another 4xx, or an answer that is not what the API returns. */
    SERVER,
}

/** Why a sign-in failed, as the phone's `AuthFailure` names it. */
enum class AuthFailure {
    /** 401 on login. */
    INVALID_CREDENTIALS,

    /** 409 on register: an account with that email exists. */
    ACCOUNT_EXISTS,

    /** 400 `weak_password` on register: under 8 characters. */
    WEAK_PASSWORD,

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

    /** 401 `invalid_google_token`: the server did not accept Google's token. */
    GOOGLE_REJECTED,

    /** 401 `email_not_verified`: Google has not verified the account's email, and no account is linked to it. */
    GOOGLE_EMAIL_UNVERIFIED,

    /** 409 `google_account_mismatch`: the email's account is linked to another Google account. */
    GOOGLE_ACCOUNT_MISMATCH,

    /** 404 `not_configured`: this server does not offer Google sign-in. */
    GOOGLE_NOT_OFFERED,

    /** Google's own sign-in could not run in this browser (blocked, closed, no account). */
    GOOGLE_FAILED,
}

sealed interface AuthResult {
    data class Success(val token: String, val email: String) : AuthResult
    data class Failed(val failure: AuthFailure) : AuthResult
}

/** Sign in and out against tung-server over `fetch`. Stores nothing; the container does. */
class WebAuth(
    private val json: Json = AuthJson,
    private val post: PostJson = ::postJson,
    private val get: GetJson = { getJson(it) },
    private val delete: PostJson = ::deleteJson,
) {

    /**
     * The Google client id [baseUrl] offers for Sign in with Google, from
     * `GET /api/v1/auth/methods`, or null: no Google there, an older server
     * (404), or no answer. The web never hardcodes one, as the phone.
     */
    suspend fun googleClientId(baseUrl: String): String? {
        val reply = try {
            get(baseUrl + METHODS_PATH)
        } catch (e: HttpUnreachable) {
            return null
        }
        if (!reply.isSuccess) return null
        return try {
            json.decodeFromString(Methods.serializer(), reply.body).google?.clientId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * `POST /api/v1/auth/google {id_token, language}`: Google's ID token for a
     * session, as the phone does; [language] (`en`, `sv`) is for the welcome
     * email, should the sign-in make a new account.
     */
    suspend fun signInWithGoogle(baseUrl: String, idToken: String, language: String? = null): AuthResult {
        val reply = try {
            post(baseUrl + GOOGLE_PATH, json.encodeToString(GoogleToken.serializer(), GoogleToken(idToken, language)), emptyMap())
        } catch (e: HttpUnreachable) {
            return AuthResult.Failed(AuthFailure.NETWORK)
        }
        if (!reply.isSuccess) {
            val code = errorCode(reply.body)
            return AuthResult.Failed(
                when (code) {
                    "invalid_google_token" -> AuthFailure.GOOGLE_REJECTED
                    "email_not_verified" -> AuthFailure.GOOGLE_EMAIL_UNVERIFIED
                    "google_account_mismatch" -> AuthFailure.GOOGLE_ACCOUNT_MISMATCH
                    "not_configured" -> AuthFailure.GOOGLE_NOT_OFFERED
                    else -> failureOf(reply.status)
                },
            )
        }
        return session(reply.body, fallbackEmail = null)
    }

    /** `POST /api/v1/auth/login` at [baseUrl]. */
    suspend fun signIn(baseUrl: String, email: String, password: String): AuthResult =
        credentials(baseUrl + LOGIN_PATH, Credentials(email, password))

    /**
     * `POST /api/v1/auth/register` at [baseUrl]: a new account, answered with
     * a session as login is. [language] (`en`, `sv`) is for the welcome email.
     */
    suspend fun register(baseUrl: String, email: String, password: String, language: String? = null): AuthResult =
        credentials(baseUrl + REGISTER_PATH, Credentials(email, password, language))

    private suspend fun credentials(url: String, credentials: Credentials): AuthResult {
        val reply = try {
            post(url, json.encodeToString(Credentials.serializer(), credentials), emptyMap())
        } catch (e: HttpUnreachable) {
            return AuthResult.Failed(AuthFailure.NETWORK)
        }
        if (!reply.isSuccess) {
            return AuthResult.Failed(
                when {
                    reply.status == 409 -> AuthFailure.ACCOUNT_EXISTS
                    reply.status == 400 && errorCode(reply.body) == "weak_password" -> AuthFailure.WEAK_PASSWORD
                    else -> failureOf(reply.status)
                },
            )
        }
        return session(reply.body, fallbackEmail = credentials.email)
    }

    private fun errorCode(body: String): String? = try {
        json.decodeFromString(ErrorBody.serializer(), body).error.code
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun session(body: String, fallbackEmail: String?): AuthResult {
        val session = try {
            json.decodeFromString(Session.serializer(), body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return AuthResult.Failed(AuthFailure.SERVER)
        }
        val email = session.user?.email ?: fallbackEmail ?: return AuthResult.Failed(AuthFailure.SERVER)
        return AuthResult.Success(session.token, email)
    }

    /** `POST /api/v1/auth/logout`: the server forgets the token. Best effort; the browser forgets it either way. */
    suspend fun signOut(baseUrl: String, token: String) {
        try {
            post(baseUrl + LOGOUT_PATH, "{}", mapOf("Authorization" to "Bearer $token"))
        } catch (e: HttpUnreachable) {
            // Offline: the token expires on the server by itself.
        }
    }

    /** `DELETE /api/v1/me {email}`: the server deletes the account and everything of it. Stores nothing. */
    suspend fun deleteAccount(baseUrl: String, token: String, email: String): DeleteAccountResult {
        val reply = try {
            delete(
                baseUrl + ACCOUNT_PATH,
                json.encodeToString(Confirmation.serializer(), Confirmation(email.trim())),
                mapOf("Authorization" to "Bearer $token"),
            )
        } catch (e: HttpUnreachable) {
            return DeleteAccountResult.NETWORK
        }
        if (reply.isSuccess) return DeleteAccountResult.DELETED
        val code = errorCode(reply.body)
        return when {
            reply.status == 401 -> DeleteAccountResult.SIGNED_OUT
            code == "email_mismatch" -> DeleteAccountResult.EMAIL_MISMATCH
            code == "session_required" -> DeleteAccountResult.NOT_ALLOWED
            reply.status == 404 || reply.status == 405 -> DeleteAccountResult.NOT_A_SERVER
            else -> DeleteAccountResult.SERVER
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
    private data class Credentials(val email: String, val password: String, val language: String? = null)

    @Serializable
    private data class Confirmation(val email: String)

    @Serializable
    private data class GoogleToken(@SerialName("id_token") val idToken: String, val language: String? = null)

    @Serializable
    private data class Methods(val google: GoogleMethod? = null)

    @Serializable
    private data class GoogleMethod(@SerialName("client_id") val clientId: String)

    @Serializable
    private data class ErrorBody(val error: ErrorDetail)

    @Serializable
    private data class ErrorDetail(val code: String)

    @Serializable
    private data class Session(val token: String, val user: SessionUser? = null)

    @Serializable
    private data class SessionUser(val email: String)

    companion object {
        val AuthJson: Json = Json { ignoreUnknownKeys = true }
    }
}
