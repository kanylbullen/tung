package io.github.kanylbullen.tung.ui.importing

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An export bigger than this is not a training log; reading stops rather than filling memory. */
private const val MAX_IMPORT_BYTES = 64L * 1024 * 1024

/**
 * The text of the document at [uri] (from the system file picker), UTF-8, on
 * IO; null when it cannot be opened or is larger than [MAX_IMPORT_BYTES].
 */
suspend fun readImportFile(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_IMPORT_BYTES) return@use null
            }
            out.toString(Charsets.UTF_8.name())
        }
    }.getOrNull()
}
