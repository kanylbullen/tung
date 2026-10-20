package io.github.kanylbullen.tung.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/** Where the server's session token lives. Reads and writes touch storage: call them off the main thread. */
interface TokenStore {
    fun read(): String?

    /** Stores [token], or forgets it for null. */
    fun write(token: String?)
}

/**
 * The token in EncryptedSharedPreferences (androidx.security 1.0.0, the
 * stable release; minSdk 26 is above its 23), under an AES-256 key in the
 * Android Keystore. Never in DataStore, never logged.
 *
 * When the file cannot be opened (a Keystore key lost after a restore from
 * backup is the known case) it is deleted and created again: the user signs
 * in once more, which beats a crash on every start.
 *
 * One secret per [file]: the sync session token in `sync_token` (the
 * one-argument constructor), the in-app assistant's API key in `ai_key`
 * (`ai/AiSettings.kt`). Each file is excluded from backups in the app's rules.
 */
class EncryptedTokenStore(
    private val context: Context,
    private val file: String,
    private val key: String,
) : TokenStore {

    /** The sync session token. */
    constructor(context: Context) : this(context, FILE, KEY)

    private val prefs: SharedPreferences by lazy {
        runCatching { open() }.getOrElse { error ->
            Log.w(TAG, "Token store unreadable, starting over", error)
            context.deleteSharedPreferences(file)
            open()
        }
    }

    private fun open(): SharedPreferences = EncryptedSharedPreferences.create(
        file,
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun read(): String? = prefs.getString(key, null)

    override fun write(token: String?) {
        prefs.edit().apply { if (token == null) remove(key) else putString(key, token) }.commit()
    }

    private companion object {
        const val TAG = "TungSync"
        const val FILE = "sync_token"
        const val KEY = "session_token"
    }
}
