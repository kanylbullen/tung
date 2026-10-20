package io.github.kanylbullen.tung.sync

import io.github.kanylbullen.tung.core.sync.SYNC_MAX_ROWS
import io.github.kanylbullen.tung.core.sync.SYNC_PATH
import io.github.kanylbullen.tung.core.sync.SyncFailure
import io.github.kanylbullen.tung.core.sync.SyncPlanner
import io.github.kanylbullen.tung.core.sync.SyncRequest
import io.github.kanylbullen.tung.core.sync.SyncResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/** The JSON of the wire: full rows (nulls and defaults written), unknown keys from a newer server ignored. */
val SyncJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/** The app's HTTP client for tung-server: OkHttp, kotlinx JSON, statuses handled by the caller. */
fun syncHttpClient(): HttpClient = HttpClient(OkHttp) {
    expectSuccess = false
    install(ContentNegotiation) { json(SyncJson) }
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 60_000
        requestTimeoutMillis = 120_000
    }
}

sealed interface SyncResult {
    /** [pushed] rows went up, [applied] rows of the responses changed something here. */
    data class Success(val pushed: Int, val applied: Int) : SyncResult

    /** No server or no token: nothing to do. */
    data object NotSignedIn : SyncResult

    data class Failed(val failure: SyncFailure) : SyncResult
}

sealed interface AuthResult {
    data class Success(val token: String, val email: String) : AuthResult
    data class Failed(val failure: AuthFailure, val status: Int? = null) : AuthResult
}

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
 * call runs on [Dispatchers.IO]; one sync runs at a time.
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
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryDelaysMillis: List<Long> = listOf(2_000L, 8_000L),
    private val onSignedOut: suspend () -> Unit = {},
) {
    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)

    /** True while a sync is in progress, for the settings screen. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * One full sync: plan (dirty rows and tombstones, in chunks of at most
     * [SYNC_MAX_ROWS]) → POST each chunk with the latest cursor → apply each
     * response in one Room transaction, clearing the dirty flags of exactly
     * the rows that chunk carried → store the cursor. While a response comes
     * back full it pulls again, so a server that pages its answer is read to
     * the end. Success and failure are recorded in [SyncSettings]; a 401
     * signs the device out.
     */
    suspend fun syncOnce(): SyncResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            _running.value = true
            try {
                runSync()
            } finally {
                _running.value = false
            }
        }
    }

    private suspend fun runSync(): SyncResult {
        val baseUrl = settings.serverUrl() ?: return SyncResult.NotSignedIn
        val token = tokens.read() ?: return SyncResult.NotSignedIn
        return try {
            val local = store.readDirty()
            var cursor = settings.cursor()
            var pushed = 0
            var applied = 0
            var last: SyncResponse? = null
            suspend fun exchange(request: SyncRequest) {
                val response = postSync(baseUrl, token, request.copy(cursor = cursor))
                applied += store.apply(response, request.changes)
                pushed += request.changes.size()
                cursor = maxOf(cursor, response.cursor)
                settings.setCursor(cursor)
                last = response
            }
            SyncPlanner.plan(local.dirty, local.tombstones, cursor).forEach { exchange(it) }
            while ((last?.changes?.size() ?: 0) >= SYNC_MAX_ROWS) exchange(SyncRequest())
            settings.recordSuccess(clock())
            SyncResult.Success(pushed, applied)
        } catch (failed: SyncFailedException) {
            if (failed.failure == SyncFailure.SIGNED_OUT) {
                tokens.write(null)
                settings.signedOut(SyncFailure.SIGNED_OUT)
                onSignedOut()
            } else {
                settings.recordFailure(failed.failure)
            }
            SyncResult.Failed(failed.failure)
        }
    }

    private suspend fun postSync(baseUrl: String, token: String, request: SyncRequest): SyncResponse {
        var attempt = 0
        while (true) {
            val failure = try {
                val response = http.post(baseUrl + SYNC_PATH) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                when {
                    response.status.isSuccess() -> return response.body<SyncResponse>()
                    response.status == HttpStatusCode.Unauthorized -> throw SyncFailedException(SyncFailure.SIGNED_OUT)
                    response.status.value in 400..499 -> throw SyncFailedException(SyncFailure.REJECTED)
                    else -> SyncFailure.SERVER
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SyncFailedException) {
                throw e
            } catch (e: IOException) {
                SyncFailure.NETWORK
            } catch (e: Exception) {
                // A body that is not a SyncResponse: the server's fault, and maybe passing.
                SyncFailure.SERVER
            }
            val wait = retryDelaysMillis.getOrNull(attempt++) ?: throw SyncFailedException(failure)
            delay(wait)
        }
    }

    /** `POST /api/v1/auth/login`, or `/register` when [create]. Stores nothing; [SyncAccount] does. */
    suspend fun authenticate(baseUrl: String, email: String, password: String, create: Boolean): AuthResult =
        withContext(Dispatchers.IO) {
            try {
                val response = http.post(baseUrl + if (create) REGISTER_PATH else LOGIN_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody(Credentials(email, password))
                }
                if (response.status.isSuccess()) {
                    val session = response.body<Session>()
                    AuthResult.Success(session.token, session.user?.email ?: email)
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
     * `POST /api/v1/link/start` with this device's token: a code another
     * device (a watch) can trade for a token of the same account. Stores nothing.
     */
    suspend fun startLink(): LinkStartResult = withContext(Dispatchers.IO) {
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
    suspend fun claimLink(baseUrl: String, code: String): LinkClaimResult = withContext(Dispatchers.IO) {
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

    /** `POST /api/v1/auth/logout`: revokes the token on the server. Best effort; signing out locally never waits on it. */
    suspend fun logout(baseUrl: String, token: String) {
        withContext(Dispatchers.IO) {
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

    @Serializable
    private data class SessionUser(val email: String? = null)

    @Serializable
    private data class ErrorBody(val error: ErrorDetail)

    @Serializable
    private data class ErrorDetail(val code: String, val message: String = "")

    private companion object {
        const val LOGIN_PATH = "/api/v1/auth/login"
        const val REGISTER_PATH = "/api/v1/auth/register"
        const val LOGOUT_PATH = "/api/v1/auth/logout"
        const val LINK_START_PATH = "/api/v1/link/start"
        const val LINK_CLAIM_PATH = "/api/v1/link/claim"
    }
}
