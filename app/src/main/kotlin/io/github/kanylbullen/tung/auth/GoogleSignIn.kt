package io.github.kanylbullen.tung.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.CancellationException
import java.security.SecureRandom
import java.util.Base64

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

/**
 * Asks Google for an ID token for tung-server's client id, which the app
 * learns from the server (`GET /api/v1/auth/methods`) and never hardcodes.
 * Made by `TungApp.createGoogleSignIn()`; the flow tests' application hands
 * in a fake.
 */
interface GoogleSignIn {
    /** [context] must be an activity: Credential Manager shows its account sheet over it. */
    suspend fun idToken(context: Context, serverClientId: String): GoogleSignInResult
}

/**
 * [GoogleSignIn] over Android Credential Manager: a [GetGoogleIdOption] for
 * [serverClientId] (the web client id the server verifies the token's
 * audience against), every account on the phone offered, nothing
 * auto-selected, and a fresh nonce per request.
 */
class AndroidGoogleSignIn : GoogleSignIn {
    private val random = SecureRandom()

    override suspend fun idToken(context: Context, serverClientId: String): GoogleSignInResult {
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .setNonce(nonce)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = CredentialManager.create(context).getCredential(context, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                GoogleSignInResult.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                GoogleSignInResult.Failed("unexpected credential type ${credential.type}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            GoogleSignInResult.Cancelled
        } catch (e: NoCredentialException) {
            GoogleSignInResult.NoAccount
        } catch (e: GetCredentialProviderConfigurationException) {
            GoogleSignInResult.Unavailable
        } catch (e: GetCredentialUnsupportedException) {
            GoogleSignInResult.Unavailable
        } catch (e: GetCredentialException) {
            GoogleSignInResult.Failed(e.type)
        } catch (e: GoogleIdTokenParsingException) {
            GoogleSignInResult.Failed("unreadable Google credential")
        }
    }
}
