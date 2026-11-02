package io.github.kanylbullen.tung.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.cinterop.ExperimentalForeignApi
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
