package io.github.kanylbullen.tung.sync

/**
 * Where a secret lives: the server's session token, the assistant's API key.
 * EncryptedSharedPreferences on Android (`EncryptedTokenStore`); the iOS one
 * (the Keychain) comes with the iOS app. Reads and writes touch storage: call
 * them off the main thread.
 */
interface TokenStore {
    fun read(): String?

    /** Stores [token], or forgets it for null. */
    fun write(token: String?)
}
