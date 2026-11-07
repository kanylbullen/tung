package io.github.kanylbullen.tung.web.sync

import io.github.kanylbullen.tung.core.sync.DEVICE_HEADER
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val LOGIN_PATH = "/api/v1/auth/login"
const val REGISTER_PATH = "/api/v1/auth/register"
const val LOGOUT_PATH = "/api/v1/auth/logout"
const val GOOGLE_PATH = "/api/v1/auth/google"
const val APPLE_PATH = "/api/v1/auth/apple"
const val METHODS_PATH = "/api/v1/auth/methods"
const val ACCOUNT_PATH = "/api/v1/me"
const val RESEND_CONFIRMATION_PATH = "/api/v1/auth/verify/resend"
const val LOGOUT_OTHERS_PATH = "/api/v1/auth/logout/others"
const val CHANGE_PASSWORD_PATH = "/api/v1/auth/password"

/** The server's bounds for a password (`weak_password` outside them). */
const val MIN_PASSWORD_LENGTH = 8
const val MAX_PASSWORD_LENGTH = 1024

/** What "Sign out other devices" gave, as the phone's `SignOutOthersOutcome` names it. */
sealed interface SignOutOthersResult {
    /** Every other token of the account was revoked; [revoked] of them. */
    data class Done(val revoked: Int) : SignOutOthersResult

    /** 401: the token is no longer valid. */
    data object SignedOut : SignOutOthersResult

    /** 404 or 405: a server from before the route. */
    data object NotAServer : SignOutOthersResult

    /** 429. */
    data object RateLimited : SignOutOthersResult

    /** No connection, refused, blocked. */
    data object Network : SignOutOthersResult

    /** 5xx, 403 `session_required`, another 4xx, or an answer that is not what the API returns. */
    data object Server : SignOutOthersResult
}

/** What changing the password gave, as the phone's `ChangePasswordOutcome` names it. */
enum class ChangePasswordResult {
    /** Changed; every other device was signed out, this browser stays. */
    CHANGED,

    /** 403 `wrong_password`: not the current password. Not a sign-out. */
    WRONG_PASSWORD,

    /** 400 `weak_password`: not [MIN_PASSWORD_LENGTH] to [MAX_PASSWORD_LENGTH] characters. */
    WEAK_PASSWORD,

    /** 409 `no_password`: the account signs in only with Google or Apple. */
    NO_PASSWORD,

    /** 401: the token is no longer valid. */
    SIGNED_OUT,

    /** 429. */
    RATE_LIMITED,

    /** 404 or 405: a server from before the route. */
    NOT_A_SERVER,

    /** No connection, refused, blocked. */
    NETWORK,

    /** 5xx, another 4xx, or an answer that is not what the API returns. */
    SERVER,
}

const val FORGOT_PASSWORD_PATH = "/api/v1/auth/password/forgot"

/** What "Forgot password?" gave, as the phone's `ForgotOutcome` names it. */
enum class ForgotResult { SENT, NOT_OFFERED, RATE_LIMITED, INVALID_EMAIL, NETWORK, FAILED }

/** What asking for a new confirmation link gave, as the phone's `ResendOutcome` names it. */
enum class ResendResult { SENT, RATE_LIMITED, NETWORK, FAILED }

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

    /** 401 `invalid_apple_token`: the server did not accept Apple's token. */
    APPLE_REJECTED,

    /** 401 `email_not_verified` from `/auth/apple`: Apple gave no address Tung can use. */
    APPLE_EMAIL_UNVERIFIED,

    /** 409 `apple_account_mismatch`: the email's account is linked to another Apple ID. */
    APPLE_ACCOUNT_MISMATCH,

    /** 404 `not_configured` from `/auth/apple`: this server does not offer Sign in with Apple. */
    APPLE_NOT_OFFERED,

    /** Apple's own sign-in could not run in this browser (blocked, failed). */
    APPLE_FAILED,
}

/** The client ids a server hands out for signing in with Google and Apple (`/auth/methods`); null where it has none. */
data class SignInMethods(val google: String?, val apple: String?)

sealed interface AuthResult {
    /** [userId] is the server's id of the user, which decides whose data is in the browser (issue #152); null from a server that leaves it out. */
    data class Success(val token: String, val email: String, val userId: String? = null) : AuthResult
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
     * The Google client id and the Apple Services ID [baseUrl] offers, from
     * `GET /api/v1/auth/methods`; each null when it offers none, and both on
     * an older server (404) or no answer. The web never hardcodes one, as the phone.
     */
    suspend fun methods(baseUrl: String): SignInMethods {
        val none = SignInMethods(null, null)
        val reply = try {
            get(baseUrl + METHODS_PATH)
        } catch (e: HttpUnreachable) {
            return none
        }
        if (!reply.isSuccess) return none
        return try {
            json.decodeFromString(Methods.serializer(), reply.body).let { SignInMethods(it.google?.clientId, it.apple?.clientId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            none
        }
    }

    /** The Google client id of [methods], for callers that only offer Google. */
    suspend fun googleClientId(baseUrl: String): String? = methods(baseUrl).google

    /**
     * `POST /api/v1/auth/google {id_token, language}`: Google's ID token for a
     * session, as the phone does; [language] (`en`, `sv`) is for the welcome
     * email, should the sign-in make a new account.
     */
    suspend fun signInWithGoogle(baseUrl: String, idToken: String, language: String? = null): AuthResult =
        idTokenSignIn(baseUrl + GOOGLE_PATH, idToken, language) { code ->
            when (code) {
                "invalid_google_token" -> AuthFailure.GOOGLE_REJECTED
                "email_not_verified" -> AuthFailure.GOOGLE_EMAIL_UNVERIFIED
                "google_account_mismatch" -> AuthFailure.GOOGLE_ACCOUNT_MISMATCH
                "not_configured" -> AuthFailure.GOOGLE_NOT_OFFERED
                else -> null
            }
        }

    /** `POST /api/v1/auth/apple {id_token, language}`: Sign in with Apple's ID token for a session, as Google's. */
    suspend fun signInWithApple(baseUrl: String, idToken: String, language: String? = null): AuthResult =
        idTokenSignIn(baseUrl + APPLE_PATH, idToken, language) { code ->
            when (code) {
                "invalid_apple_token" -> AuthFailure.APPLE_REJECTED
                "email_not_verified" -> AuthFailure.APPLE_EMAIL_UNVERIFIED
                "apple_account_mismatch" -> AuthFailure.APPLE_ACCOUNT_MISMATCH
                "not_configured" -> AuthFailure.APPLE_NOT_OFFERED
                else -> null
            }
        }

    private suspend fun idTokenSignIn(url: String, idToken: String, language: String?, failure: (String?) -> AuthFailure?): AuthResult {
        val reply = try {
            post(url, json.encodeToString(IdToken.serializer(), IdToken(idToken, language)), emptyMap())
        } catch (e: HttpUnreachable) {
            return AuthResult.Failed(AuthFailure.NETWORK)
        }
        if (!reply.isSuccess) return AuthResult.Failed(failure(errorCode(reply.body)) ?: failureOf(reply.status))
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
        return AuthResult.Success(session.token, email, session.user?.id)
    }

    /** `POST /api/v1/auth/logout`: the server forgets the token. Best effort; the browser forgets it either way. */
    suspend fun signOut(baseUrl: String, token: String) {
        try {
            post(baseUrl + LOGOUT_PATH, "{}", mapOf("Authorization" to "Bearer $token"))
        } catch (e: HttpUnreachable) {
            // Offline: the token expires on the server by itself.
        }
    }

    /**
     * `POST /api/v1/auth/password/forgot {email, language}`: a link to choose
     * a new password, mailed if an account has [email]; the answer is the
     * same either way. Stores nothing.
     */
    suspend fun forgotPassword(baseUrl: String, email: String, language: String?): ForgotResult {
        val reply = try {
            post(baseUrl + FORGOT_PASSWORD_PATH, json.encodeToString(Forgot.serializer(), Forgot(email, language)), emptyMap())
        } catch (e: HttpUnreachable) {
            return ForgotResult.NETWORK
        }
        return when {
            reply.isSuccess -> ForgotResult.SENT
            reply.status == 404 -> ForgotResult.NOT_OFFERED
            reply.status == 429 -> ForgotResult.RATE_LIMITED
            reply.status == 400 -> ForgotResult.INVALID_EMAIL
            else -> ForgotResult.FAILED
        }
    }

    /** `POST /api/v1/auth/verify/resend {language}`: a new link to confirm the account's email. Stores nothing. */
    suspend fun resendConfirmation(baseUrl: String, token: String, language: String?): ResendResult {
        val reply = try {
            post(
                baseUrl + RESEND_CONFIRMATION_PATH,
                json.encodeToString(Resend.serializer(), Resend(language)),
                mapOf("Authorization" to "Bearer $token"),
            )
        } catch (e: HttpUnreachable) {
            return ResendResult.NETWORK
        }
        return when {
            reply.isSuccess -> ResendResult.SENT
            reply.status == 429 -> ResendResult.RATE_LIMITED
            else -> ResendResult.FAILED
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

    /**
     * `POST /api/v1/auth/logout/others` with this browser's [deviceId]: every
     * other token of the account stops working, this one stays. Stores nothing.
     */
    suspend fun signOutOthers(baseUrl: String, token: String, deviceId: String): SignOutOthersResult {
        val reply = try {
            post(baseUrl + LOGOUT_OTHERS_PATH, "{}", mapOf("Authorization" to "Bearer $token", DEVICE_HEADER to deviceId))
        } catch (e: HttpUnreachable) {
            return SignOutOthersResult.Network
        }
        return when {
            reply.isSuccess -> try {
                SignOutOthersResult.Done(json.decodeFromString(SignedOutOthers.serializer(), reply.body).revokedTokens)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SignOutOthersResult.Server
            }
            reply.status == 401 -> SignOutOthersResult.SignedOut
            reply.status == 429 -> SignOutOthersResult.RateLimited
            reply.status == 404 || reply.status == 405 -> SignOutOthersResult.NotAServer
            else -> SignOutOthersResult.Server
        }
    }

    /**
     * `POST /api/v1/auth/password {current_password, new_password}` with this
     * browser's [deviceId]: the server changes the password and signs every
     * other device out. A 403 `wrong_password` is not a sign-out. Stores nothing.
     */
    suspend fun changePassword(baseUrl: String, token: String, deviceId: String, current: String, new: String): ChangePasswordResult {
        val reply = try {
            post(
                baseUrl + CHANGE_PASSWORD_PATH,
                json.encodeToString(PasswordChange.serializer(), PasswordChange(current, new)),
                mapOf("Authorization" to "Bearer $token", DEVICE_HEADER to deviceId),
            )
        } catch (e: HttpUnreachable) {
            return ChangePasswordResult.NETWORK
        }
        if (reply.isSuccess) return ChangePasswordResult.CHANGED
        val code = errorCode(reply.body)
        return when {
            reply.status == 401 -> ChangePasswordResult.SIGNED_OUT
            code == "wrong_password" -> ChangePasswordResult.WRONG_PASSWORD
            code == "weak_password" -> ChangePasswordResult.WEAK_PASSWORD
            code == "no_password" -> ChangePasswordResult.NO_PASSWORD
            reply.status == 429 -> ChangePasswordResult.RATE_LIMITED
            reply.status == 404 || reply.status == 405 -> ChangePasswordResult.NOT_A_SERVER
            else -> ChangePasswordResult.SERVER
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
    private data class PasswordChange(
        @SerialName("current_password") val currentPassword: String,
        @SerialName("new_password") val newPassword: String,
    )

    @Serializable
    private data class SignedOutOthers(@SerialName("revoked_tokens") val revokedTokens: Int = 0)


    @Serializable
    private data class Resend(val language: String? = null)

    @Serializable
    private data class Forgot(val email: String, val language: String? = null)

    @Serializable
    private data class IdToken(@SerialName("id_token") val idToken: String, val language: String? = null)

    @Serializable
    private data class Methods(val google: ProviderMethod? = null, val apple: ProviderMethod? = null)

    @Serializable
    private data class ProviderMethod(@SerialName("client_id") val clientId: String)

    @Serializable
    private data class ErrorBody(val error: ErrorDetail)

    @Serializable
    private data class ErrorDetail(val code: String)

    @Serializable
    private data class Session(val token: String, val user: SessionUser? = null)

    @Serializable
    private data class SessionUser(val email: String, val id: String? = null)

    companion object {
        val AuthJson: Json = Json { ignoreUnknownKeys = true }
    }
}
