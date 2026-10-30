package io.github.kanylbullen.tung.sync

/** A [TokenStore] in memory, for the JVM tests: Robolectric has no Android Keystore. */
class MemoryTokenStore(@Volatile var token: String? = null) : TokenStore {
    override fun read(): String? = token

    override fun write(token: String?) {
        this.token = token
    }
}
