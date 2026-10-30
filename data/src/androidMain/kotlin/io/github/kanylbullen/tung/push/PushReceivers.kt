package io.github.kanylbullen.tung.push

import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.github.kanylbullen.tung.core.sync.PushTransport
import io.github.kanylbullen.tung.data.TungHost
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.MessagingReceiver

/**
 * Whatever arrives by push is the server's "sync now" (`{"type":"sync"}`,
 * never content), so receiving is one thing on both transports: a sync, as
 * soon as there is a network, if signed in.
 */
internal fun onPushReceived(context: Context) {
    (context.applicationContext as? TungHost)?.container?.syncAccount?.requestSync()
}

private fun registrar(context: Context): Pair<TungHost, PushRegistrar>? {
    val host = context.applicationContext as? TungHost ?: return null
    return host to host.container.push
}

/** FCM: any message enqueues a sync; a new token is registered with the server. */
class TungFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) = onPushReceived(this)

    override fun onNewToken(token: String) {
        val (host, push) = registrar(this) ?: return
        host.container.appScope.launch { push.onNewEndpoint(PushTransport.FCM, token) }
    }
}

/** UnifiedPush (connector 2.x): a message enqueues a sync; a new endpoint is registered with the server. */
class TungUnifiedPushReceiver : MessagingReceiver() {
    override fun onMessage(context: Context, message: ByteArray, instance: String) = onPushReceived(context)

    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        val (host, push) = registrar(context) ?: return
        host.container.appScope.launch { push.onNewEndpoint(PushTransport.UNIFIED_PUSH, endpoint) }
    }

    override fun onUnregistered(context: Context, instance: String) {
        val (host, push) = registrar(context) ?: return
        host.container.appScope.launch { push.onUnregistered() }
    }

    override fun onRegistrationFailed(context: Context, instance: String) = Unit
}
