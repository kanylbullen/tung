package io.github.kanylbullen.tung.data

import io.github.kanylbullen.tung.sync.TokenStore
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Issue #238: the Keychain outlives an uninstall, the app's directory does
 * not. With no database file yet (a fresh install) the stored secrets are
 * forgotten; with one (an update) they are kept. The Keychain itself cannot be
 * reached from a test process (`errSecNotAvailable`), so the stores are fakes.
 */
@OptIn(ExperimentalForeignApi::class)
class FreshInstallTest {

    private class MemoryStore(var value: String?) : TokenStore {
        override fun read(): String? = value
        override fun write(token: String?) { value = token }
    }

    private fun tempPath() = NSTemporaryDirectory() + "tung-fresh-${Random.nextLong().toULong()}.db"

    @Test
    fun aFreshInstallForgetsTheOldSignInAndKey() {
        val token = MemoryStore("tung_old")
        val key = MemoryStore("sk-old")
        forgetKeychainOnFreshInstall(tempPath(), listOf(token, key))
        assertNull(token.read())
        assertNull(key.read())
    }

    @Test
    fun anUpdateKeepsTheSignIn() {
        val path = tempPath()
        NSFileManager.defaultManager.createFileAtPath(path, contents = null, attributes = null)
        try {
            val token = MemoryStore("tung_kept")
            forgetKeychainOnFreshInstall(path, listOf(token))
            assertEquals("tung_kept", token.read())
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        }
    }
}
