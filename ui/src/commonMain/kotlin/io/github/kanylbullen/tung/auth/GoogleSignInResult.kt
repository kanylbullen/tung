package io.github.kanylbullen.tung.auth

/** What asking Google for an ID token gave. */
sealed interface GoogleSignInResult {
    data class Token(val idToken: String) : GoogleSignInResult

    /** The user closed the account sheet: nothing to say. */
    data object Cancelled : GoogleSignInResult

    /** No Google account on the phone (or none it may use). */
    data object NoAccount : GoogleSignInResult

    /** No Credential Manager provider: no Play services, or too old. */
    data object Unavailable : GoogleSignInResult

    data class Failed(val reason: String?) : GoogleSignInResult
}
