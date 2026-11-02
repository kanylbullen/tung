package io.github.kanylbullen.tung.auth

/** What asking Apple for an ID token gave (Sign in with Apple, on an iPhone). */
sealed interface AppleSignInResult {
    data class Token(val idToken: String) : AppleSignInResult

    /** The person closed Apple's sheet: nothing to say. */
    data object Cancelled : AppleSignInResult

    data class Failed(val reason: String?) : AppleSignInResult
}
