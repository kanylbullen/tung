package io.github.kanylbullen.tung.core.sync

/**
 * The account a device's local log belongs to (issue #152): the server and
 * the user id that server gave at sign-in. Never the email: an email can
 * change, and Sign in with Google or Apple has none typed. Recorded after a
 * sign-in and kept across sign-outs, so the next sign-in knows whose data
 * is still on the device.
 */
data class DataOwner(val serverUrl: String, val userId: String) {
    /** The same account: the same server (a trailing slash aside) and the same user id. */
    fun sameAs(other: DataOwner): Boolean = serverKey(serverUrl) == serverKey(other.serverUrl) && userId == other.userId
}

/** What a sign-in does with the log already on the device. */
enum class LocalDataOnSignIn {
    /** Upload it, as before issue #152: the same account again, a first sign-in, or nothing to upload. */
    UPLOAD,

    /** The device holds another account's data: ask before anything is uploaded or merged. */
    ASK,
}

/**
 * The rule of issue #152. [recorded] is the owner stored on the device,
 * [incoming] the account the server just accepted (null when the server's
 * answer had no user id, which counts as a different account). With
 * nothing to upload ([hasLocalData] false) there is nothing to ask about.
 * An answer without a user id (an older server) falls back on the last
 * sign-in's server and email, as a device without an owner does.
 *
 * A device signed in before owners were recorded has none; there the
 * server and email of the last sign-in ([previousServerUrl],
 * [previousEmail]) stand in, and only to ask more, never less: the same
 * server and email upload as before, anything else asks. A device that
 * never signed in ([previousEmail] null) uploads, as a first sign-in always did.
 */
fun localDataOnSignIn(
    recorded: DataOwner?,
    incoming: DataOwner?,
    hasLocalData: Boolean,
    previousServerUrl: String? = null,
    previousEmail: String? = null,
    incomingEmail: String? = null,
    /** The server signed in to; [incoming]'s when there is one, needed for an answer without a user id. */
    incomingServerUrl: String? = incoming?.serverUrl,
): LocalDataOnSignIn {
    val sameLastSignIn = previousEmail != null && previousServerUrl != null && incomingServerUrl != null && incomingEmail != null &&
        serverKey(previousServerUrl) == serverKey(incomingServerUrl) && previousEmail.trim().equals(incomingEmail.trim(), ignoreCase = true)
    return when {
        !hasLocalData -> LocalDataOnSignIn.UPLOAD
        recorded != null && incoming != null -> if (recorded.sameAs(incoming)) LocalDataOnSignIn.UPLOAD else LocalDataOnSignIn.ASK
        recorded == null && previousEmail == null -> LocalDataOnSignIn.UPLOAD
        sameLastSignIn -> LocalDataOnSignIn.UPLOAD
        else -> LocalDataOnSignIn.ASK
    }
}

private fun serverKey(url: String): String = url.trim().trimEnd('/').lowercase()

/**
 * A sign-in the server accepted, held until the user answers what happens
 * to another account's data on the device: who signed in, where, and how
 * many local changes ([unsynced]: dirty rows and tombstones) never reached
 * any account, which "Delete them" would lose.
 */
data class PendingSignIn(val serverUrl: String, val email: String, val unsynced: Int = 0)

/** What happens to another account's data on the device when a new account signs in. */
enum class OtherAccountChoice {
    /** "Delete them": the local training data goes, then the new account syncs down. */
    DELETE,

    /** "Add them to <email>": everything local is uploaded to the new account, as before issue #152. */
    ADD,
}
