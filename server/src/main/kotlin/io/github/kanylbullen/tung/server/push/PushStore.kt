package io.github.kanylbullen.tung.server.push

import io.github.kanylbullen.tung.core.newId
import io.github.kanylbullen.tung.core.sync.PushEndpoint
import io.github.kanylbullen.tung.core.sync.PushEndpointRequest
import io.github.kanylbullen.tung.core.sync.PushTransport
import io.github.kanylbullen.tung.server.Db
import io.github.kanylbullen.tung.server.PushEndpoints
import io.github.kanylbullen.tung.server.badRequest
import io.github.kanylbullen.tung.server.conflict
import io.github.kanylbullen.tung.server.notFound
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.sql.Connection

/** The error code of an endpoint the server refuses to call (SSRF guard, or not a usable URL). */
const val INVALID_ENDPOINT = "invalid_endpoint"

const val MAX_DEVICE_ID = 128
const val MAX_ENDPOINT = 4096

/** Active endpoints one user may have: a handful of devices, with room; bounds what one write fans out to. */
const val MAX_ENDPOINTS_PER_USER = 20

/** A registered endpoint as the push service needs it. */
data class Target(val id: String, val deviceId: String, val transport: String, val endpoint: String)

/**
 * `push_endpoints` (V6): one row per user and device, revoked rather than
 * deleted. Registering again from the same device replaces its transport
 * and endpoint in place, keeps the id and clears `revoked_at`.
 */
class PushStore(
    private val db: Db,
    private val guard: EndpointGuard = EndpointGuard(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** The registered endpoint and whether it is new (201) rather than replaced (200). */
    data class Registered(val endpoint: PushEndpoint, val created: Boolean)

    suspend fun register(userId: String, request: PushEndpointRequest): Registered {
        val deviceId = request.deviceId.trim()
        val transport = request.transport.trim()
        val endpoint = request.endpoint.trim()
        validatePushEndpoint(deviceId, transport, endpoint)
        if (transport == PushTransport.UNIFIED_PUSH) {
            // May resolve the host: off the request thread.
            withContext(Dispatchers.IO) { guard.refusal(endpoint) }?.let {
                throw badRequest("The server will not send pushes to this endpoint: $it.", INVALID_ENDPOINT)
            }
        }
        val now = clock()
        return db.tx {
            val connection = connection.connection as Connection
            val active = PushEndpoints.selectAll()
                .where { (PushEndpoints.userId eq userId) and PushEndpoints.revokedAt.isNull() and (PushEndpoints.deviceId neq deviceId) }
                .count()
            if (active >= MAX_ENDPOINTS_PER_USER) {
                throw conflict(
                    "too_many_endpoints",
                    "This account has $MAX_ENDPOINTS_PER_USER active push endpoints. Delete one under $PATH before registering another device.",
                )
            }
            // One statement: concurrent registrations of the same device cannot both insert. xmax = 0 on a fresh insert.
            connection.prepareStatement(
                """
                INSERT INTO push_endpoints (id, user_id, device_id, transport, endpoint, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, device_id) DO UPDATE
                    SET transport = EXCLUDED.transport, endpoint = EXCLUDED.endpoint, revoked_at = NULL
                RETURNING id, device_id, transport, endpoint, created_at, last_used_at, (xmax = 0) AS inserted
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, newId())
                statement.setString(2, userId)
                statement.setString(3, deviceId)
                statement.setString(4, transport)
                statement.setString(5, endpoint)
                statement.setLong(6, now)
                statement.executeQuery().use { r ->
                    r.next()
                    val lastUsed = r.getLong("last_used_at").takeUnless { r.wasNull() }
                    Registered(
                        PushEndpoint(r.getString("id"), r.getString("device_id"), r.getString("transport"), r.getString("endpoint"), r.getLong("created_at"), lastUsed),
                        created = r.getBoolean("inserted"),
                    )
                }
            }
        }
    }

    /** The user's active endpoints, oldest first. */
    suspend fun list(userId: String): List<PushEndpoint> = db.tx {
        PushEndpoints.selectAll()
            .where { (PushEndpoints.userId eq userId) and PushEndpoints.revokedAt.isNull() }
            .orderBy(PushEndpoints.createdAt to SortOrder.ASC, PushEndpoints.id to SortOrder.ASC)
            .map(::toDto)
    }

    /** Revokes the user's endpoint [id]; 404 when there is no such active endpoint of theirs. */
    suspend fun revoke(userId: String, id: String) {
        val now = clock()
        val changed = db.tx {
            PushEndpoints.update({ (PushEndpoints.userId eq userId) and (PushEndpoints.id eq id) and PushEndpoints.revokedAt.isNull() }) {
                it[revokedAt] = now
            }
        }
        if (changed == 0) throw notFound("Push endpoint")
    }

    /** What a push to the user goes to: every active endpoint. */
    suspend fun targets(userId: String): List<Target> = db.tx {
        PushEndpoints.selectAll()
            .where { (PushEndpoints.userId eq userId) and PushEndpoints.revokedAt.isNull() }
            .map { Target(it[PushEndpoints.id], it[PushEndpoints.deviceId], it[PushEndpoints.transport], it[PushEndpoints.endpoint]) }
    }

    suspend fun markUsed(userId: String, target: Target) {
        val now = clock()
        db.tx {
            PushEndpoints.update({ (PushEndpoints.userId eq userId) and (PushEndpoints.id eq target.id) }) { it[lastUsedAt] = now }
        }
    }

    /** Revokes [target], unless the device has registered another endpoint since it was sent to. */
    suspend fun markGone(userId: String, target: Target) {
        val now = clock()
        db.tx {
            PushEndpoints.update({
                (PushEndpoints.userId eq userId) and (PushEndpoints.id eq target.id) and
                    (PushEndpoints.endpoint eq target.endpoint) and PushEndpoints.revokedAt.isNull()
            }) { it[revokedAt] = now }
        }
    }

    private fun toDto(row: ResultRow) = PushEndpoint(
        id = row[PushEndpoints.id],
        deviceId = row[PushEndpoints.deviceId],
        transport = row[PushEndpoints.transport],
        endpoint = row[PushEndpoints.endpoint],
        createdAt = row[PushEndpoints.createdAt],
        lastUsedAt = row[PushEndpoints.lastUsedAt],
    )

    private companion object {
        const val PATH = "/api/v1/push/endpoints"
    }
}

/**
 * The shape of a registration: a device id of 1 to [MAX_DEVICE_ID]
 * characters, a known transport, and an endpoint of 1 to [MAX_ENDPOINT]
 * characters: an FCM token without spaces, or for UnifiedPush an http(s)
 * URL without user info or fragment; whether the server may call it is
 * [EndpointGuard]'s, checked in [PushStore.register]. A query is allowed,
 * since distributors put one in their URLs (ntfy's `?up=1`).
 */
fun validatePushEndpoint(deviceId: String, transport: String, endpoint: String) {
    if (deviceId.isEmpty() || deviceId.length > MAX_DEVICE_ID) throw badRequest("device_id must be 1 to $MAX_DEVICE_ID characters.")
    if (deviceId.any { it.isISOControl() }) throw badRequest("device_id must not contain control characters.")
    if (transport !in PushTransport.ALL) throw badRequest("transport must be one of ${PushTransport.ALL.joinToString()}, got '$transport'.")
    if (endpoint.isEmpty() || endpoint.length > MAX_ENDPOINT) throw badRequest("endpoint must be 1 to $MAX_ENDPOINT characters.")
    when (transport) {
        PushTransport.FCM ->
            if (endpoint.any { it.isWhitespace() || it.isISOControl() }) throw badRequest("endpoint must be an FCM registration token, without spaces.")
        PushTransport.UNIFIED_PUSH -> {
            val uri = runCatching { URI(endpoint) }.getOrNull()
            val scheme = uri?.scheme?.lowercase()
            val host = uri?.host
            if (uri == null || host == null || (scheme != "https" && scheme != "http") || uri.rawUserInfo != null || uri.rawFragment != null) {
                throw badRequest("endpoint must be the distributor's https:// URL for a unifiedpush endpoint.", INVALID_ENDPOINT)
            }
            // https only, and never to an internal address, unless the instance allows it: EndpointGuard, in register.
        }
    }
}
