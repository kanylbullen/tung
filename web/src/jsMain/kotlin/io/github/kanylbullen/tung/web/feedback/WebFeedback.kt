package io.github.kanylbullen.tung.web.feedback

import io.github.kanylbullen.tung.web.sync.HttpUnreachable
import io.github.kanylbullen.tung.web.sync.PostJson
import io.github.kanylbullen.tung.web.sync.postJson
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLMetaElement
import org.w3c.files.Blob
import org.w3c.files.FileReader
import kotlin.js.Promise
import kotlin.math.max
import kotlin.math.roundToInt

const val FEEDBACK_PATH = "/api/v1/feedback"

/** As the server: three screenshots, 5000 characters. */
const val MAX_FEEDBACK_IMAGES = 3
const val MAX_FEEDBACK_MESSAGE = 5_000

/** A screenshot bigger than this, in bytes or on its longest side, is scaled down before it is sent. */
private const val SEND_AS_IS_BYTES = 1_500_000
private const val MAX_SIDE = 2_000

enum class FeedbackKind(val wire: String) { BUG("bug"), IDEA("idea") }

/** What sending gave. */
enum class FeedbackResult { SENT, TOO_LARGE, TOO_MANY_TODAY, SIGNED_OUT, NETWORK, FAILED }

@Serializable
private data class FeedbackBody(
    val kind: String,
    val message: String,
    val platform: String,
    @SerialName("app_version") val appVersion: String?,
    val images: List<String>,
)

@Serializable
private data class ErrorBody(val error: ErrorDetail)

@Serializable
private data class ErrorDetail(val code: String)

/** Feedback from the web: `POST /api/v1/feedback` with the screenshots as data URLs. */
class WebFeedback(private val post: PostJson = ::postJson, private val json: Json = Json { ignoreUnknownKeys = true }) {

    suspend fun send(baseUrl: String, token: String, kind: FeedbackKind, message: String, images: List<String>, appVersion: String?): FeedbackResult {
        val body = json.encodeToString(FeedbackBody.serializer(), FeedbackBody(kind.wire, message.trim(), "web", appVersion, images))
        val reply = try {
            post(baseUrl + FEEDBACK_PATH, body, mapOf("Authorization" to "Bearer $token"))
        } catch (e: HttpUnreachable) {
            return FeedbackResult.NETWORK
        }
        if (reply.isSuccess) return FeedbackResult.SENT
        val code = try {
            json.decodeFromString(ErrorBody.serializer(), reply.body).error.code
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return when {
            reply.status == 401 -> FeedbackResult.SIGNED_OUT
            reply.status == 413 || code == "image_too_large" -> FeedbackResult.TOO_LARGE
            reply.status == 429 -> FeedbackResult.TOO_MANY_TODAY
            else -> FeedbackResult.FAILED
        }
    }
}

/** The build this page is, from `<meta name="tung-build">` (the service worker's id), else null. */
fun webBuildId(): String? =
    (document.querySelector("meta[name=tung-build]") as? HTMLMetaElement)?.content?.takeIf { it.isNotBlank() && !it.startsWith("@") }

/**
 * A screenshot (pasted or picked) as a data URL to send: as it is when it is
 * small enough, else drawn onto a canvas at most [MAX_SIDE] on its longest
 * side and written as JPEG. Null when the browser cannot read it as an image.
 */
suspend fun screenshotDataUrl(blob: Blob): String? {
    if (!blob.type.startsWith("image/")) return null
    val original = readAsDataUrl(blob) ?: return null
    val bitmap = try {
        js("createImageBitmap")(blob).unsafeCast<Promise<dynamic>>().await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        return null
    }
    val width = (bitmap.width as Number).toInt()
    val height = (bitmap.height as Number).toInt()
    val sendable = blob.type in setOf("image/png", "image/jpeg", "image/webp")
    if (sendable && blob.size.toInt() <= SEND_AS_IS_BYTES && max(width, height) <= MAX_SIDE) return original
    val scale = minOf(1.0, MAX_SIDE.toDouble() / max(width, height))
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    canvas.width = (width * scale).roundToInt().coerceAtLeast(1)
    canvas.height = (height * scale).roundToInt().coerceAtLeast(1)
    val context = canvas.getContext("2d").asDynamic()
    context.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
    bitmap.close()
    return canvas.toDataURL("image/jpeg", 0.88)
}

private suspend fun readAsDataUrl(blob: Blob): String? = Promise<String?> { resolve, _ ->
    val reader = FileReader()
    reader.onload = { resolve(reader.result as? String) }
    reader.onerror = { resolve(null) }
    reader.readAsDataURL(blob)
}.await()
