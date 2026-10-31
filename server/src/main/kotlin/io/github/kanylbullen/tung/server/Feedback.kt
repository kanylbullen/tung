package io.github.kanylbullen.tung.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.util.Base64
import java.util.UUID

const val MAX_FEEDBACK_MESSAGE = 5_000
const val MAX_FEEDBACK_IMAGES = 3

/** One screenshot, after decoding: a phone's full-size PNG fits, and the clients scale larger ones down first. */
const val MAX_FEEDBACK_IMAGE_BYTES = 4 * 1024 * 1024

/** How many a user may send in [FEEDBACK_WINDOW_MILLIS]; more is 429 `too_much_feedback`. */
const val MAX_FEEDBACK_PER_DAY = 20
const val FEEDBACK_WINDOW_MILLIS = 24 * 60 * 60 * 1000L

/** The most rows kept: past it the server answers 503 `feedback_full` rather than grow without end. */
const val MAX_FEEDBACK_ROWS = 10_000

/** The most an admin listing carries, newest first. */
const val MAX_FEEDBACK_LISTED = 500

/** A body bigger than this is refused before it is read: three screenshots in base64 and the text. */
private const val MAX_FEEDBACK_BODY_BYTES = 18L * 1024 * 1024

val FEEDBACK_KINDS = setOf("bug", "idea")
val FEEDBACK_PLATFORMS = setOf("android", "wear", "web", "ios")

@Serializable
data class FeedbackInput(
    val kind: String,
    val message: String,
    val platform: String,
    val appVersion: String? = null,
    /** Each a screenshot as base64 (PNG, JPEG or WebP); the type is read from the bytes. */
    val images: List<String> = emptyList(),
)

@Serializable
data class FeedbackCreated(val id: String)

@Serializable
data class FeedbackImageDto(val id: String, val contentType: String, val size: Int)

@Serializable
data class FeedbackDto(
    val id: String,
    val kind: String,
    val message: String,
    val platform: String,
    val appVersion: String?,
    val createdAt: Long,
    val resolvedAt: Long?,
    val userId: String,
    val email: String,
    val images: List<FeedbackImageDto>,
)

@Serializable
data class FeedbackList(val feedback: List<FeedbackDto>, val openCount: Int)

@Serializable
data class FeedbackPatch(val resolved: Boolean)

/** A screenshot's bytes and type, for the admin route that serves it. */
class FeedbackImage(val contentType: String, val bytes: ByteArray)

/**
 * Feedback from a signed-in user (a bug or an idea, with up to three
 * screenshots), and the admins' list of it. Plain JDBC: the images are
 * bytea, which Exposed's DSL here does not need to know about.
 */
class FeedbackStore(private val db: Db, private val admins: Admins, private val clock: () -> Long) {

    suspend fun create(principal: TokenPrincipal, input: FeedbackInput): FeedbackCreated {
        val kind = input.kind.trim().lowercase()
        if (kind !in FEEDBACK_KINDS) throw badRequest("kind must be one of ${FEEDBACK_KINDS.joinToString()}.", "invalid_kind")
        val platform = input.platform.trim().lowercase()
        if (platform !in FEEDBACK_PLATFORMS) throw badRequest("platform must be one of ${FEEDBACK_PLATFORMS.joinToString()}.", "invalid_platform")
        val message = input.message.trim()
        if (message.isEmpty()) throw badRequest("Write what happened or what you would like.", "empty_message")
        if (message.length > MAX_FEEDBACK_MESSAGE) throw badRequest("At most $MAX_FEEDBACK_MESSAGE characters.", "message_too_long")
        val version = input.appVersion?.trim()?.take(64)?.takeIf { it.isNotEmpty() }
        if (input.images.size > MAX_FEEDBACK_IMAGES) throw badRequest("At most $MAX_FEEDBACK_IMAGES screenshots.", "too_many_images")
        val images = input.images.map { decodeImage(it) }

        val now = clock()
        val id = UUID.randomUUID().toString()
        db.tx {
            val connection = connection.connection as Connection
            val total = connection.count("SELECT count(*) FROM feedback")
            if (total >= MAX_FEEDBACK_ROWS) {
                throw ApiException(HttpStatusCode.ServiceUnavailable, "feedback_full", "Feedback is full for now. Try again later.")
            }
            val recent = connection.prepareStatement("SELECT count(*) FROM feedback WHERE user_id = ? AND created_at > ?").use {
                it.setString(1, principal.userId)
                it.setLong(2, now - FEEDBACK_WINDOW_MILLIS)
                it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
            if (recent >= MAX_FEEDBACK_PER_DAY) {
                throw ApiException(HttpStatusCode.TooManyRequests, "too_much_feedback", "That is a lot of feedback for one day. Try again tomorrow.")
            }
            connection.prepareStatement(
                "INSERT INTO feedback (id, user_id, kind, message, platform, app_version, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            ).use {
                it.setString(1, id)
                it.setString(2, principal.userId)
                it.setString(3, kind)
                it.setString(4, message)
                it.setString(5, platform)
                it.setString(6, version)
                it.setLong(7, now)
                it.executeUpdate()
            }
            images.forEachIndexed { position, image ->
                connection.prepareStatement(
                    "INSERT INTO feedback_images (id, feedback_id, position, content_type, data) VALUES (?, ?, ?, ?, ?)",
                ).use {
                    it.setString(1, UUID.randomUUID().toString())
                    it.setString(2, id)
                    it.setInt(3, position)
                    it.setString(4, image.contentType)
                    it.setBytes(5, image.bytes)
                    it.executeUpdate()
                }
            }
        }
        return FeedbackCreated(id)
    }

    suspend fun list(principal: TokenPrincipal): FeedbackList {
        admins.require(principal)
        return db.tx {
            val connection = connection.connection as Connection
            val images = mutableMapOf<String, MutableList<FeedbackImageDto>>()
            val rows = connection.prepareStatement(
                """
                SELECT f.id, f.kind, f.message, f.platform, f.app_version, f.created_at, f.resolved_at, f.user_id, u.email
                FROM feedback f JOIN users u ON u.id = f.user_id
                ORDER BY f.created_at DESC, f.id LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, MAX_FEEDBACK_LISTED)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                FeedbackDto(
                                    id = rows.getString("id"),
                                    kind = rows.getString("kind"),
                                    message = rows.getString("message"),
                                    platform = rows.getString("platform"),
                                    appVersion = rows.getString("app_version"),
                                    createdAt = rows.getLong("created_at"),
                                    resolvedAt = rows.getLong("resolved_at").takeUnless { rows.wasNull() },
                                    userId = rows.getString("user_id"),
                                    email = rows.getString("email"),
                                    images = emptyList(),
                                ),
                            )
                        }
                    }
                }
            }
            if (rows.isNotEmpty()) {
                connection.prepareStatement(
                    "SELECT id, feedback_id, content_type, octet_length(data) AS size FROM feedback_images WHERE feedback_id = ANY (?) ORDER BY feedback_id, position",
                ).use { statement ->
                    statement.setArray(1, connection.createArrayOf("text", rows.map { it.id }.toTypedArray()))
                    statement.executeQuery().use { result ->
                        while (result.next()) {
                            images.getOrPut(result.getString("feedback_id")) { mutableListOf() }
                                .add(FeedbackImageDto(result.getString("id"), result.getString("content_type"), result.getInt("size")))
                        }
                    }
                }
            }
            val open = connection.count("SELECT count(*) FROM feedback WHERE resolved_at IS NULL")
            FeedbackList(rows.map { it.copy(images = images[it.id].orEmpty()) }, open)
        }
    }

    suspend fun image(principal: TokenPrincipal, feedbackId: String, imageId: String): FeedbackImage {
        admins.require(principal)
        return db.tx {
            val connection = connection.connection as Connection
            connection.prepareStatement("SELECT content_type, data FROM feedback_images WHERE id = ? AND feedback_id = ?").use {
                it.setString(1, imageId)
                it.setString(2, feedbackId)
                it.executeQuery().use { rows ->
                    if (!rows.next()) throw notFound("Screenshot")
                    FeedbackImage(rows.getString("content_type"), rows.getBytes("data"))
                }
            }
        }
    }

    suspend fun setResolved(principal: TokenPrincipal, feedbackId: String, resolved: Boolean) {
        admins.require(principal)
        val changed = db.tx {
            val connection = connection.connection as Connection
            connection.prepareStatement("UPDATE feedback SET resolved_at = ? WHERE id = ?").use {
                if (resolved) it.setLong(1, clock()) else it.setNull(1, java.sql.Types.BIGINT)
                it.setString(2, feedbackId)
                it.executeUpdate()
            }
        }
        if (changed == 0) throw notFound("Feedback")
    }

    suspend fun delete(principal: TokenPrincipal, feedbackId: String) {
        admins.require(principal)
        db.tx {
            val connection = connection.connection as Connection
            connection.prepareStatement("DELETE FROM feedback WHERE id = ?").use {
                it.setString(1, feedbackId)
                it.executeUpdate()
            }
        }
    }

    private fun Connection.count(sql: String): Int =
        prepareStatement(sql).use { it.executeQuery().use { rows -> rows.next(); rows.getInt(1) } }

    private fun decodeImage(encoded: String): FeedbackImage {
        // A data URL is taken too, since that is what a browser hands over.
        val payload = encoded.substringAfter("base64,", encoded).trim()
        val bytes = try {
            Base64.getMimeDecoder().decode(payload)
        } catch (e: IllegalArgumentException) {
            throw badRequest("A screenshot is not valid base64.", "invalid_image")
        }
        if (bytes.size > MAX_FEEDBACK_IMAGE_BYTES) throw imageTooLarge()
        val type = imageType(bytes) ?: throw badRequest("A screenshot must be a PNG, JPEG or WebP image.", "invalid_image")
        return FeedbackImage(type, bytes)
    }

    private fun imageTooLarge() =
        ApiException(HttpStatusCode.PayloadTooLarge, "image_too_large", "A screenshot may be at most ${MAX_FEEDBACK_IMAGE_BYTES / (1024 * 1024)} MB.")

    companion object {
        /** The type by the file's own signature, never by what the client claims. */
        fun imageType(bytes: ByteArray): String? {
            fun at(offset: Int, vararg expected: Int) =
                bytes.size >= offset + expected.size && expected.indices.all { bytes[offset + it].toInt() and 0xFF == expected[it] }
            return when {
                at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
                at(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
                at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50) -> "image/webp"
                else -> null
            }
        }
    }
}

/** `POST /feedback` for any signed-in user (a write token), behind the bearer token. */
fun Route.feedbackRoutes(store: FeedbackStore) {
    post("/feedback") {
        val length = call.request.contentLength()
        if (length != null && length > MAX_FEEDBACK_BODY_BYTES) {
            throw ApiException(HttpStatusCode.PayloadTooLarge, "image_too_large", "The feedback is too large. Send fewer or smaller screenshots.")
        }
        call.respond(HttpStatusCode.Created, store.create(call.user(), call.receive()))
    }
}

/** The admins' list, behind the bearer token: `/admin/feedback`, one, its screenshots, resolve and delete. */
fun Route.adminFeedbackRoutes(store: FeedbackStore) {
    route("/admin/feedback") {
        get { call.respond(store.list(call.user())) }
        route("/{id}") {
            patch {
                store.setResolved(call.user(), call.pathId(), call.receive<FeedbackPatch>().resolved)
                call.respond(HttpStatusCode.NoContent)
            }
            delete {
                store.delete(call.user(), call.pathId())
                call.respond(HttpStatusCode.NoContent)
            }
            get("/images/{imageId}") {
                val imageId = call.parameters["imageId"] ?: throw badRequest("Missing path parameter imageId.")
                val image = store.image(call.user(), call.pathId(), imageId)
                call.response.header(HttpHeaders.CacheControl, "private, max-age=3600")
                call.response.header("X-Content-Type-Options", "nosniff")
                call.response.header(HttpHeaders.ContentDisposition, "inline")
                call.respondBytes(image.bytes, ContentType.parse(image.contentType))
            }
        }
    }
}
