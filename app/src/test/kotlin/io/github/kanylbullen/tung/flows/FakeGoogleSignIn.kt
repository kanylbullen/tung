package io.github.kanylbullen.tung.flows

import android.content.Context
import io.github.kanylbullen.tung.auth.GoogleSignIn
import io.github.kanylbullen.tung.auth.GoogleSignInResult
import java.util.concurrent.CopyOnWriteArrayList

/** Google as the flow tests see it: answers [result] at once, and records the client id it was asked for. */
class FakeGoogleSignIn : GoogleSignIn {
    @Volatile var result: GoogleSignInResult = GoogleSignInResult.Token("google-id-token")

    val requestedClientIds = CopyOnWriteArrayList<String>()

    override suspend fun idToken(context: Context, serverClientId: String): GoogleSignInResult {
        requestedClientIds += serverClientId
        return result
    }
}
