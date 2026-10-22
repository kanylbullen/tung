package io.github.kanylbullen.tung.core.sync

/** How this device can be woken by the server, as [selectPushTransport] decides it. */
sealed interface PushChoice {
    /** Firebase Cloud Messaging: Play services and a Firebase app in this build. */
    data object Fcm : PushChoice

    /** UnifiedPush through the distributor app [distributor] (a package name; ntfy is the usual one). */
    data class UnifiedPush(val distributor: String) : PushChoice

    /** Neither: the periodic sync, every 15 minutes, and a sync when the app opens. */
    data object None : PushChoice
}

/**
 * The push transport for this device, automatically: FCM when Google Play
 * services are available **and** the build has a Firebase app configured
 * (a self-built APK without `google-services.json` has none); else UnifiedPush
 * when a distributor is installed, the one the user chose ([chosen]) while it
 * is still installed, otherwise the first in [distributors]' order; else none.
 */
fun selectPushTransport(
    playServices: Boolean,
    firebaseConfigured: Boolean,
    distributors: List<String>,
    chosen: String? = null,
): PushChoice = when {
    playServices && firebaseConfigured -> PushChoice.Fcm
    distributors.isEmpty() -> PushChoice.None
    chosen != null && chosen in distributors -> PushChoice.UnifiedPush(chosen)
    else -> PushChoice.UnifiedPush(distributors.first())
}
