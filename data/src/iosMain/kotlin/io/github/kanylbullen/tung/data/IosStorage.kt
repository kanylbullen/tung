package io.github.kanylbullen.tung.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.cinterop.ExperimentalForeignApi
import io.github.kanylbullen.tung.sync.TokenStore
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * The app's own directory for the database and the settings: Application
 * Support, which iOS keeps, backs up and never shows in Files. Made the
 * first time it is asked for.
 */
@OptIn(ExperimentalForeignApi::class)
fun iosAppDirectory(): String {
    val manager = NSFileManager.defaultManager
    val url = manager.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null)
        ?: error("No Application Support directory")
    manager.createDirectoryAtURL(url, withIntermediateDirectories = true, attributes = null, error = null)
    return requireNotNull((url as NSURL).path)
}

/** The `settings` Preferences DataStore in [directory], the phone's `settings` file. */
fun iosSettingsDataStore(directory: String): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(produceFile = { "$directory/settings.preferences_pb".toPath() })

/**
 * A fresh install forgets what an earlier install left in the Keychain
 * (issue #238). iOS keeps Keychain items when an app is deleted, while the
 * app's own directory goes with it, so a reinstall would find the old session
 * token without the account it belongs to: signed in as no one. A fresh
 * install is one whose [database] file is not there yet; an update keeps it
 * and keeps the sign-in. Call before the database is first opened.
 */
fun forgetKeychainOnFreshInstall(database: String, stores: List<TokenStore>) {
    if (NSFileManager.defaultManager.fileExistsAtPath(database)) return
    stores.forEach { it.write(null) }
}
