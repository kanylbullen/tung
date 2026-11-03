package io.github.kanylbullen.tung.core.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Push (issue #34): a device registers where the server can wake it, and the
// server sends an empty "sync now" there whenever another client changes the
// user's data. Never content: the device syncs to learn what changed.

const val PUSH_ENDPOINTS_PATH = "/api/v1/push/endpoints"

/** The transports a push endpoint can use; the wire values of `transport`. */
object PushTransport {
    /** Firebase Cloud Messaging: `endpoint` is the registration token. */
    const val FCM = "fcm"

    /** UnifiedPush (ntfy and other distributors): `endpoint` is the URL the server POSTs to. */
    const val UNIFIED_PUSH = "unifiedpush"

    /**
     * Apple Push Notification service (the iPhone): `endpoint` is the device token in hex. The
     * server sends a silent background push; a server without APNs configured keeps the
     * endpoint and sends nothing. A client registers it only with a server that knows it (an
     * older one answers 400 and the device syncs on its own schedule, as without push).
     */
    const val APNS = "apns"

    val ALL: Set<String> = setOf(FCM, UNIFIED_PUSH, APNS)
}

/** `POST /api/v1/push/endpoints`: registers or replaces the endpoint of [deviceId]. */
@Serializable
data class PushEndpointRequest(
    @SerialName("device_id") val deviceId: String,
    val transport: String,
    val endpoint: String,
)

/** One registered endpoint, as the server lists and returns it. */
@Serializable
data class PushEndpoint(
    val id: String,
    @SerialName("device_id") val deviceId: String,
    val transport: String,
    val endpoint: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("last_used_at") val lastUsedAt: Long? = null,
)

/** `GET /api/v1/push/endpoints`. */
@Serializable
data class PushEndpointList(val endpoints: List<PushEndpoint>)

/** The whole push message, on both transports: wake up and sync. */
@Serializable
data class PushMessage(val type: String = TYPE_SYNC) {
    companion object {
        const val TYPE_SYNC = "sync"
    }
}
