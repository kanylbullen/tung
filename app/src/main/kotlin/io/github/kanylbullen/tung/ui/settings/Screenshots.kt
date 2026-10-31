package io.github.kanylbullen.tung.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** A screenshot this small, in bytes and on its longest side, goes as it is; a larger one is scaled down. */
private const val SEND_AS_IS_BYTES = 1_500_000
private const val MAX_SIDE = 2_000
private const val THUMBNAIL_SIDE = 288

/** At most what is read of one file: past it the picture is not a screenshot. */
private const val MAX_READ_BYTES = 40 * 1024 * 1024

/**
 * The picked image at [uri] as a [Screenshot]: the file itself when it is a
 * PNG, JPEG or WebP no larger than [SEND_AS_IS_BYTES] and [MAX_SIDE], else
 * decoded, scaled to [MAX_SIDE] on its longest side and written as JPEG.
 * Null when it cannot be read as an image.
 */
suspend fun readScreenshot(context: Context, uri: Uri): Screenshot? = withContext(Dispatchers.IO) {
    runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_READ_BYTES) return@runCatching null
            }
            out.toByteArray()
        } ?: return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val longest = max(bounds.outWidth, bounds.outHeight)
        val sendable = bounds.outMimeType in setOf("image/png", "image/jpeg", "image/webp")
        val sent = if (sendable && bytes.size <= SEND_AS_IS_BYTES && longest <= MAX_SIDE) {
            bytes
        } else {
            val decoded = decodeScaled(bytes, longest, MAX_SIDE) ?: return@runCatching null
            ByteArrayOutputStream().also { decoded.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
        }
        val thumbnail = decodeScaled(bytes, longest, THUMBNAIL_SIDE) ?: return@runCatching null
        Screenshot(sent, thumbnail.asImageBitmap())
    }.getOrNull()
}

/** [bytes] decoded at most [side] on the longest side: a power-of-two sample first, then an exact scale. */
private fun decodeScaled(bytes: ByteArray, longest: Int, side: Int): Bitmap? {
    var sample = 1
    while (longest / (sample * 2) >= side) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val now = max(bitmap.width, bitmap.height)
    if (now <= side) return bitmap
    val scale = side.toDouble() / now
    return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1), (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
}
