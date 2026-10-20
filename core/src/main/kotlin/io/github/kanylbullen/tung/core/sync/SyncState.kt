package io.github.kanylbullen.tung.core.sync

/**
 * Where sync stands on one device: the server [cursor] of the last applied
 * response (0 before the first sync), when the last sync succeeded (epoch
 * millis, null if never), and the last failure, a [SyncFailure] key, or null
 * after a success.
 */
data class SyncState(
    val cursor: Long = 0,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
)

/** Why a sync failed, by a stable key the app stores and turns into words. */
enum class SyncFailure(val key: String) {
    /** No connection, a timeout, or a connection refused. Retried. */
    NETWORK("network"),

    /** The server answered 5xx. Retried. */
    SERVER("server"),

    /** The server answered 401: the token is no longer valid. The device is signed out. */
    SIGNED_OUT("signed_out"),

    /** The server refused the request with another 4xx. Not retried until something changes. */
    REJECTED("rejected"),
    ;

    companion object {
        fun fromKey(key: String?): SyncFailure? = entries.firstOrNull { it.key == key }
    }
}
