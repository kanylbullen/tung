package io.github.kanylbullen.tung.sync

import io.github.kanylbullen.tung.core.sync.DEVICE_HEADER
import io.github.kanylbullen.tung.core.sync.PUSH_ENDPOINTS_PATH
import io.github.kanylbullen.tung.core.sync.PushEndpoint
import io.github.kanylbullen.tung.core.sync.PushEndpointRequest
import io.github.kanylbullen.tung.core.sync.SYNC_PATH
import io.github.kanylbullen.tung.core.sync.SyncFailure
import io.github.kanylbullen.tung.core.sync.SyncLoop
import io.github.kanylbullen.tung.core.sync.SyncReply
import io.github.kanylbullen.tung.core.sync.SyncResponse
import io.github.kanylbullen.tung.core.sync.SyncResult
import io.github.kanylbullen.tung.core.sync.SyncSession
import io.github.kanylbullen.tung.core.sync.SyncTransport
import io.github.kanylbullen.tung.data.ioDispatcher
import io.github.kanylbullen.tung.data.systemMillis
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The JSON of the wire: full rows (nulls and defaults written), unknown keys from a newer server ignored. */
val SyncJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * The app's HTTP client for tung-server: the platform's engine (OkHttp on
 * Android, NSURLSession on iOS), kotlinx JSON ([SyncJson]), statuses handled
 * by the caller; 15 s to connect, 60 s of silence, 120 s for a request.
 */
expect fun syncHttpClient(): HttpClient

sealed interface AuthResult {
    /** [googleLinked]: the account has a Google account linked (the server's `user.google_linked`). */
    data class Success(val token: String, val email: String, val googleLinked: Boolean = false) : AuthResult
    data class Failed(val failure: AuthFailure, val status: Int? = null) : AuthResult
}

/** `GET /api/v1/auth/methods`: password sign-in, and Google's with the client id to ask Google for, or null. */
data class AuthMethods(val password: Boolean, val googleClientId: String?)

sealed interface AuthMethodsResult {
    data class Success(val methods: AuthMethods) : AuthMethodsResult
    data class Failed(val failure: AuthFailure) : AuthMethodsResult
}

/** `GET /api/v1/me`: the signed-in account as the server has it. */
data class AccountInfo(val email: String, val hasPassword: Boolean, val googleLinked: Boolean)

enum class AuthFailure {
    /** 401 on login. */
    INVALID_CREDENTIALS,

    /** 409 on register. */
    ACCOUNT_EXISTS,

    /** 400 `weak_password`: under 8 characters. */
    WEAK_PASSWORD,

    /** Another 400, most likely the email. */
    INVALID_REQUEST,

    /** 429. */
    RATE_LIMITED,

    /** 404 or 405: something answers, but not tung-server's API. */
    NOT_A_SERVER,

    /** 401 `invalid_google_token` on Google sign-in: the server did not accept Google's token. */
    GOOGLE_REJECTED,

    /** 401 `email_not_verified`: Google has not verified the account's email, and no account is linked to it. */
    GOOGLE_EMAIL_UNVERIFIED,

    /** 409 `google_account_mismatch`: the email's account is linked to another Google account. */
    GOOGLE_ACCOUNT_MISMATCH,

    /** 404 `not_configured`: this server does not offer Google sign-in. */
    GOOGLE_NOT_OFFERED,

    /** No connection, refused, timed out. */
    NETWORK,

    /** 5xx, or an answer that is not what the API returns. */
    SERVER,
}

/** What `POST /api/v1/link/start` gave: a six-digit [code] to show, valid until [expiresAt] (epoch millis). */
data class LinkCode(val code: String, val expiresAt: Long)

/** What `POST /api/v1/link/claim` gave: a token for [email]'s account, or why not. */
sealed interface LinkClaimResult {
    data class Success(val token: String, val email: String) : LinkClaimResult
    data class Failed(val failure: LinkFailure) : LinkClaimResult
}

sealed interface LinkStartResult {
    data class Success(val code: LinkCode) : LinkStartResult
    data class Failed(val failure: LinkFailure) : LinkStartResult
}

enum class LinkFailure {
    /** 404 `invalid_code` on claim: unknown, expired or already used. */
    INVALID_CODE,

    /** No server or token on this device, so it cannot ask for a code. */
    NOT_SIGNED_IN,

    /** 401 on start: the token is no longer valid. */
    SIGNED_OUT,

    /** 429. */
    RATE_LIMITED,

    /** Another 404 or a 405: not tung-server, or one from before link codes. */
    NOT_A_SERVER,

    /** No connection, refused, timed out. */
    NETWORK,

    /** 5xx, another 4xx, or an answer that is not what the API returns. */
    SERVER,
}

/**
 * Talks to tung-server: sign in and out (`/api/v1/auth/…`) and [syncOnce]
 * (`POST /api/v1/sync`, the protocol in `core/sync/SyncProtocol.kt`). Every
 * call runs on [ioDispatcher]; one sync runs at a time.
 *
 * @param retryDelaysMillis the waits between attempts of one sync request
 *   that failed with a 5xx or an I/O error; a 4xx is never retried.
 * @param onSignedOut called after a 401 has cleared the token.
 */
class SyncClient(
    private val store: SyncStore,
    private val settings: SyncSettings,
    private val tokens: TokenStore,
    private val http: HttpClient,
    private val clock: () -> Long = ::systemMillis,
    private val retryDelaysMillis: List<Long> = listOf(2_000L, 8_000L),
    private val onSignedOut: suspend () -> Unit = {},
) {
    /** Sync itself is core's [SyncLoop]; this class gives it Room, the settings and Ktor. */
    private val loop = SyncLoop(
        local = store,
        session = object : SyncSession {
            override suspend fun serverUrl() = settings.serverUrl()
            override suspend fun token() = tokens.read()
            override suspend fun deviceId() = settings.deviceId()
            override suspend fun cursor() = settings.cursor()
            override suspend fun setCursor(cursor: Long) { settings.setCursor(cursor) }
            override suspend fun recordSuccess(at: Long) { settings.recordSuccess(at) }
            override suspend fun recordFailure(failure: SyncFailure) { settings.recordFailure(failure) }
            override suspend fun signedOutByServer() {
                tokens.write(null)
                settings.signedOut(SyncFailure.SIGNED_OUT)
            }
        },
        transport = SyncTransport { baseUrl, token, deviceId, request ->
            try {
                val response = http.post(baseUrl + SYNC_PATH) {
                    bearerAuth(token)
                    header(DEVICE_HEADER, deviceId)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                if (!response.status.isSuccess()) {
                    SyncReply.Status(response.status.value)
                } else {
                    try {
                        SyncReply.Answer(response.body<SyncResponse>())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        SyncReply.Unreadable
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                SyncReply.Unreachable
            } catch (e: Exception) {
                // Anything else from the client is the answer's fault, as before: a server error, retried.
                SyncReply.Unreadable
            }
        },
        clock = clock,
        retryDelaysMillis = retryDelaysMillis,
        onSignedOut = onSignedOut,
    )

    /** True while a sync is in progress, for the settings screen. */
    val running: StateFlow<Boolean> = loop.running

    /**
     * One full sync, core's [SyncLoop]: plan, POST each chunk with the
     * latest cursor, apply each response in one Room transaction, store the
     * cursor, pull again while a response is full. Success and failure are
     * recorded in [SyncSettings]; a 401 signs the device out.
     */
    suspend fun syncOnce(): SyncResult = withContext(ioDispatcher) { loop.syncOnce() }

    /** `POST /api/v1/auth/login`, or `/register` when [create]. Stores nothing; [SyncAccount] does. */
    suspend fun authenticate(baseUrl: String, email: String, password: String, create: Boolean): AuthResult =
        withContext(ioDispatcher) {
            try {
                val response = http.post(baseUrl + if (create) REGISTER_PATH else LOGIN_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody(Credentials(email, password))
                }
                if (response.status.isSuccess()) {
                    val session = response.body<Session>()
                    AuthResult.Success(session.token, session.user?.email ?: email, session.user?.googleLinked == true)
                } else {
                    AuthResult.Failed(authFailure(response), response.status.value)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                AuthResult.Failed(AuthFailure.NETWORK)
            } catch (e: Exception) {
                AuthResult.Failed(AuthFailure.SERVER)
            }
        }

    /**
     * `GET /api/v1/auth/methods` at [baseUrl]: whether the server offers Google
     * sign-in and with which client id. A server from before the route (404)
     * offers the password only.
     */
    suspend fun authMethods(baseUrl: String): AuthMethodsResult = withContext(ioDispatcher) {
        try {
            val response = http.get(baseUrl + METHODS_PATH)
            when {
                response.status.isSuccess() -> {
                    val body = response.body<MethodsBody>()
                    AuthMethodsResult.Success(AuthMethods(body.password, body.google?.clientId?.takeIf { it.isNotBlank() }))
                }
                response.status.value == 404 -> AuthMethodsResult.Success(AuthMethods(password = true, googleClientId = null))
                response.status.value == 405 -> AuthMethodsResult.Failed(AuthFailure.NOT_A_SERVER)
                response.status.value == 429 -> AuthMethodsResult.Failed(AuthFailure.RATE_LIMITED)
                else -> AuthMethodsResult.Failed(AuthFailure.SERVER)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            AuthMethodsResult.Failed(AuthFailure.NETWORK)
        } catch (e: Exception) {
            AuthMethodsResult.Failed(AuthFailure.SERVER)
        }
    }

    /**
     * `POST /api/v1/auth/google {id_token}` at [baseUrl]: a Google ID token
     * for the server's client id, traded for a session token as `/login`
     * answers. Stores nothing; [SyncAccount] does.
     */
    suspend fun loginWithGoogle(baseUrl: String, idToken: String): AuthResult = withContext(ioDispatcher) {
        try {
            val response = http.post(baseUrl + GOOGLE_PATH) {
                contentType(ContentType.Application.Json)
                setBody(GoogleBody(idToken))
            }
            if (response.status.isSuccess()) {
                val session = response.body<Session>()
                AuthResult.Success(session.token, session.user?.email.orEmpty(), session.user?.googleLinked ?: true)
            } else {
                val code = runCatching { response.body<ErrorBody>().error.code }.getOrNull()
                AuthResult.Failed(
                    when {
                        code == "email_not_verified" -> AuthFailure.GOOGLE_EMAIL_UNVERIFIED
                        code == "google_account_mismatch" -> AuthFailure.GOOGLE_ACCOUNT_MISMATCH
                        code == "not_configured" -> AuthFailure.GOOGLE_NOT_OFFERED
                        response.status.value == 401 -> AuthFailure.GOOGLE_REJECTED
                        response.status.value == 400 -> AuthFailure.INVALID_REQUEST
                        else -> authFailure(response)
                    },
                    response.status.value,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            AuthResult.Failed(AuthFailure.NETWORK)
        } catch (e: Exception) {
            AuthResult.Failed(AuthFailure.SERVER)
        }
    }

    /** `GET /api/v1/me` with this device's token; null when signed out, offline or refused. */
    suspend fun me(): AccountInfo? = withContext(ioDispatcher) {
        val baseUrl = settings.serverUrl() ?: return@withContext null
        val token = tokens.read() ?: return@withContext null
        try {
            val response = http.get(baseUrl + ME_PATH) { bearerAuth(token) }
            if (!response.status.isSuccess()) return@withContext null
            val user = response.body<SessionUser>()
            AccountInfo(user.email.orEmpty(), user.hasPassword ?: true, user.googleLinked ?: false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * `POST /api/v1/link/start` with this device's token: a code another
     * device (a watch) can trade for a token of the same account. Stores nothing.
     */
    suspend fun startLink(): LinkStartResult = withContext(ioDispatcher) {
        val baseUrl = settings.serverUrl() ?: return@withContext LinkStartResult.Failed(LinkFailure.NOT_SIGNED_IN)
        val token = tokens.read() ?: return@withContext LinkStartResult.Failed(LinkFailure.NOT_SIGNED_IN)
        try {
            val response = http.post(baseUrl + LINK_START_PATH) { bearerAuth(token) }
            if (response.status.isSuccess()) {
                val body = response.body<LinkCodeBody>()
                LinkStartResult.Success(LinkCode(body.code, body.expiresAt))
            } else {
                LinkStartResult.Failed(
                    when (response.status.value) {
                        401 -> LinkFailure.SIGNED_OUT
                        429 -> LinkFailure.RATE_LIMITED
                        404, 405 -> LinkFailure.NOT_A_SERVER
                        else -> LinkFailure.SERVER
                    },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            LinkStartResult.Failed(LinkFailure.NETWORK)
        } catch (e: Exception) {
            LinkStartResult.Failed(LinkFailure.SERVER)
        }
    }

    /**
     * `POST /api/v1/link/claim {code}` at [baseUrl], without a token: a valid
     * code comes back as a token for its account. Stores nothing; [SyncAccount] does.
     */
    suspend fun claimLink(baseUrl: String, code: String): LinkClaimResult = withContext(ioDispatcher) {
        try {
            val response = http.post(baseUrl + LINK_CLAIM_PATH) {
                contentType(ContentType.Application.Json)
                setBody(LinkClaimBody(code))
            }
            if (response.status.isSuccess()) {
                val session = response.body<Session>()
                LinkClaimResult.Success(session.token, session.user?.email.orEmpty())
            } else {
                val errorCode = runCatching { response.body<ErrorBody>().error.code }.getOrNull()
                LinkClaimResult.Failed(
                    when {
                        errorCode == "invalid_code" -> LinkFailure.INVALID_CODE
                        response.status.value == 429 -> LinkFailure.RATE_LIMITED
                        response.status.value == 404 || response.status.value == 405 -> LinkFailure.NOT_A_SERVER
                        else -> LinkFailure.SERVER
                    },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            LinkClaimResult.Failed(LinkFailure.NETWORK)
        } catch (e: Exception) {
            LinkClaimResult.Failed(LinkFailure.SERVER)
        }
    }

    /**
     * `POST /api/v1/push/endpoints`: tells the server where to wake this
     * device ([transport] and [endpoint]: an FCM token or a UnifiedPush URL),
     * under this device's id; the server replaces an earlier one of the same
     * device. The endpoint's server id, or null when it did not take it.
     */
    suspend fun registerPushEndpoint(transport: String, endpoint: String): String? = withContext(ioDispatcher) {
        val baseUrl = settings.serverUrl() ?: return@withContext null
        val token = tokens.read() ?: return@withContext null
        try {
            val response = http.post(baseUrl + PUSH_ENDPOINTS_PATH) {
                bearerAuth(token)
                header(DEVICE_HEADER, settings.deviceId())
                contentType(ContentType.Application.Json)
                setBody(PushEndpointRequest(settings.deviceId(), transport, endpoint))
            }
            if (response.status.isSuccess()) response.body<PushEndpoint>().id else null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** `DELETE /api/v1/push/endpoints/{id}` while signed in: the device has no push transport any more. Best effort. */
    suspend fun deletePushEndpoint(id: String) {
        val baseUrl = settings.serverUrl() ?: return
        val token = tokens.read() ?: return
        deletePushEndpoint(baseUrl, token, id)
    }

    /** `DELETE /api/v1/push/endpoints/{id}` with [token] at [baseUrl], before signing out. Best effort. */
    suspend fun deletePushEndpoint(baseUrl: String, token: String, id: String) {
        withContext(ioDispatcher) {
            try {
                http.delete("$baseUrl$PUSH_ENDPOINTS_PATH/$id") { bearerAuth(token) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline: the server drops it when its transport answers 404/410, or keeps waking a signed-out app, which then does nothing.
            }
        }
    }

    /** `POST /api/v1/auth/logout`: revokes the token on the server. Best effort; signing out locally never waits on it. */
    suspend fun logout(baseUrl: String, token: String) {
        withContext(ioDispatcher) {
            try {
                http.post(baseUrl + LOGOUT_PATH) { bearerAuth(token) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline or gone: the token stays valid on the server until revoked from another device.
            }
        }
    }

    private suspend fun authFailure(response: HttpResponse): AuthFailure = when (response.status.value) {
        401 -> AuthFailure.INVALID_CREDENTIALS
        409 -> AuthFailure.ACCOUNT_EXISTS
        429 -> AuthFailure.RATE_LIMITED
        404, 405 -> AuthFailure.NOT_A_SERVER
        400 -> {
            val code = runCatching { response.body<ErrorBody>().error.code }.getOrNull()
            if (code == "weak_password") AuthFailure.WEAK_PASSWORD else AuthFailure.INVALID_REQUEST
        }
        in 400..499 -> AuthFailure.INVALID_REQUEST
        else -> AuthFailure.SERVER
    }

    private class SyncFailedException(val failure: SyncFailure) : Exception(failure.key)

    @Serializable
    private data class Credentials(val email: String, val password: String)

    @Serializable
    private data class LinkCodeBody(val code: String, @SerialName("expires_at") val expiresAt: Long)

    @Serializable
    private data class LinkClaimBody(val code: String)

    @Serializable
    private data class Session(val token: String, val user: SessionUser? = null)

    /** The server's user; the flags are null from a server from before Google sign-in. */
    @Serializable
    private data class SessionUser(
        val email: String? = null,
        @SerialName("has_password") val hasPassword: Boolean? = null,
        @SerialName("google_linked") val googleLinked: Boolean? = null,
    )

    @Serializable
    private data class GoogleBody(@SerialName("id_token") val idToken: String)

    @Serializable
    private data class MethodsBody(val password: Boolean = true, val google: GoogleMethodBody? = null)

    @Serializable
    private data class GoogleMethodBody(@SerialName("client_id") val clientId: String)

    @Serializable
    private data class ErrorBody(val error: ErrorDetail)

    @Serializable
    private data class ErrorDetail(val code: String, val message: String = "")

    private companion object {
        const val LOGIN_PATH = "/api/v1/auth/login"
        const val REGISTER_PATH = "/api/v1/auth/register"
        const val LOGOUT_PATH = "/api/v1/auth/logout"
        const val GOOGLE_PATH = "/api/v1/auth/google"
        const val METHODS_PATH = "/api/v1/auth/methods"
        const val ME_PATH = "/api/v1/me"
        const val LINK_START_PATH = "/api/v1/link/start"
        const val LINK_CLAIM_PATH = "/api/v1/link/claim"
    }
}
