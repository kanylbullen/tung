package io.github.kanylbullen.tung.push

import java.util.concurrent.CopyOnWriteArrayList

/**
 * A device's push transports, as a test says: by default none (no Play
 * services, no Firebase app, no distributor), which is also what Robolectric
 * would report. [registeredWith] records every distributor asked for an
 * endpoint; the test answers for it through [TungUnifiedPushReceiver].
 */
class FakePushEnvironment(
    @Volatile var playServices: Boolean = false,
    @Volatile var firebaseConfigured: Boolean = false,
    @Volatile var installedDistributors: List<String> = emptyList(),
    @Volatile var token: String? = null,
    @Volatile var labels: Map<String, String> = emptyMap(),
) : PushEnvironment {
    val registeredWith = CopyOnWriteArrayList<String>()

    @Volatile var unregistered = 0

    override fun playServicesAvailable() = playServices
    override fun firebaseConfigured() = firebaseConfigured
    override fun distributors() = installedDistributors
    override fun distributorLabel(packageName: String) = labels[packageName] ?: packageName
    override suspend fun fcmToken() = token

    override fun registerUnifiedPush(distributor: String) {
        registeredWith += distributor
    }

    override fun unregisterUnifiedPush() {
        unregistered++
    }
}
