package io.github.kanylbullen.tung.ui

import io.github.kanylbullen.tung.data.IosContainer
import io.github.kanylbullen.tung.ui.resources.Res
import io.github.kanylbullen.tung.ui.resources.rest_notification_text
import io.github.kanylbullen.tung.ui.resources.rest_notification_title
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSData

// What the Xcode app (iosApp/) calls besides MainViewController: the app
// delegate hands over the APNs device token and asks for a sync when a silent
// push or a background refresh wakes the app. Either can start the process
// with no screen at all, so the container is not tied to the view.

/**
 * One container per process, as the phone's `TungApp` holds one. What it
 * needs from the app bundle comes from ui's resources: the catalogue
 * (`files/catalog/…`, iOS-only Compose Resources copied from `catalog/` at
 * build time) and the "Rest over" notification's text.
 */
@OptIn(ExperimentalResourceApi::class)
internal val appContainer by lazy {
    IosContainer(
        readCatalogue = { path -> Res.readBytes("files/catalog/$path").decodeToString() },
        restOverTitle = { getString(Res.string.rest_notification_title) },
        restOverText = { getString(Res.string.rest_notification_text) },
    )
}

/**
 * The device token from `application(_:didRegisterForRemoteNotificationsWithDeviceToken:)`,
 * as Swift calls it: `IosAppKt.onApnsToken(token:)`. Kept and registered with the server
 * as an `apns` endpoint while signed in.
 */
fun onApnsToken(token: NSData) {
    val hex = token.toHex()
    appContainer.appScope.launch { appContainer.onPushToken(hex) }
}

/**
 * One sync for a silent push (`application(_:didReceiveRemoteNotification:fetchCompletionHandler:)`)
 * or a background refresh (`BGAppRefreshTask`); [done] says whether it reached the server,
 * on the main thread, where Swift hands it to iOS's completion handler.
 */
fun syncInBackground(done: (Boolean) -> Unit) {
    appContainer.appScope.launch {
        val ok = runCatching { appContainer.syncInBackground() }.getOrDefault(false)
        withContext(Dispatchers.Main) { done(ok) }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toHex(): String {
    val bytes = this.bytes?.reinterpret<kotlinx.cinterop.UByteVar>() ?: return ""
    return buildString(length.toInt() * 2) {
        for (i in 0 until length.toInt()) {
            val b = bytes[i].toInt()
            append("0123456789abcdef"[b shr 4])
            append("0123456789abcdef"[b and 0xF])
        }
    }
}
