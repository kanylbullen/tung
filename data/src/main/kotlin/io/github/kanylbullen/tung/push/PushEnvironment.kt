package io.github.kanylbullen.tung.push

import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import org.unifiedpush.android.connector.UnifiedPush
import kotlin.coroutines.resume

/**
 * What [PushRegistrar] asks the device: which transports exist and how to
 * register with them. [AndroidPushEnvironment] in the apps; a fake in tests,
 * which have neither Play services nor a distributor.
 */
interface PushEnvironment {
    /** Google Play services are installed and usable. */
    fun playServicesAvailable(): Boolean

    /** This build has a Firebase app: it was built with a `google-services.json`. */
    fun firebaseConfigured(): Boolean

    /** Installed UnifiedPush distributors, by package name, in a stable order. */
    fun distributors(): List<String>

    /** A distributor's name as the user knows it ("ntfy"). */
    fun distributorLabel(packageName: String): String

    /** The FCM registration token, or null when it cannot be had now. */
    suspend fun fcmToken(): String?

    /** Asks [distributor] for an endpoint; it arrives in [TungUnifiedPushReceiver.onNewEndpoint]. */
    fun registerUnifiedPush(distributor: String)

    fun unregisterUnifiedPush()
}

class AndroidPushEnvironment(context: Context) : PushEnvironment {
    private val context: Context = context.applicationContext ?: context

    override fun playServicesAvailable(): Boolean = runCatching {
        GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    override fun firebaseConfigured(): Boolean = runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)

    override fun distributors(): List<String> = runCatching {
        UnifiedPush.getDistributors(context).filter { it != context.packageName }.sorted()
    }.getOrDefault(emptyList())

    override fun distributorLabel(packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    override suspend fun fcmToken(): String? = suspendCancellableCoroutine { continuation ->
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                continuation.resume(if (task.isSuccessful) task.result else null)
            }
        } catch (_: IllegalStateException) {
            // No default FirebaseApp after all.
            continuation.resume(null)
        }
    }

    override fun registerUnifiedPush(distributor: String) {
        UnifiedPush.saveDistributor(context, distributor)
        UnifiedPush.registerApp(context)
    }

    override fun unregisterUnifiedPush() {
        runCatching { UnifiedPush.unregisterApp(context) }
    }
}
