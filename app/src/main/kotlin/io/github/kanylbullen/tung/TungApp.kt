package io.github.kanylbullen.tung

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import io.github.kanylbullen.tung.auth.AndroidGoogleSignIn
import io.github.kanylbullen.tung.auth.GoogleSignIn
import io.github.kanylbullen.tung.data.TungContainer
import io.github.kanylbullen.tung.health.AndroidHealthConnectGateway
import io.github.kanylbullen.tung.data.TungHost
import io.github.kanylbullen.tung.voice.AndroidSpeechInput
import io.github.kanylbullen.tung.voice.SpeechInput
import io.github.kanylbullen.tung.wear.DataLayerStateWriter
import io.github.kanylbullen.tung.wear.WearCommandHandler
import io.github.kanylbullen.tung.wear.WearStatePublisher
import io.github.kanylbullen.tung.wear.isWearableApiAvailable

/**
 * The phone app. Its data layer is a [TungContainer] (module `data/`, shared
 * with the watch); what only the phone has, the Data Layer link to a watch
 * running in companion mode, is built here.
 */
open class TungApp : Application(), Configuration.Provider, TungHost {
    override val container: TungContainer by lazy { createContainer() }

    /** Mirrors the active workout to a watch in companion mode (`wear/`); started in [onCreate] and by `WearSyncService`. */
    val wearPublisher: WearStatePublisher by lazy {
        with(container) {
            WearStatePublisher(this@TungApp, workoutRepository, routineRepository, restTimer, settings.data, DataLayerStateWriter(this@TungApp), syncSettings.serverUrl)
        }
    }
    val wearCommands: WearCommandHandler by lazy {
        WearCommandHandler(container.workoutRepository, container.routineRepository, container.restTimer, wearPublisher.selection)
    }

    private var startedActivities = 0

    override val isInForeground: Boolean get() = startedActivities > 0

    override val notificationIcon: Int get() = R.drawable.ic_dumbbell

    override fun openWorkoutIntent(workoutId: String): Intent =
        Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_WORKOUT, workoutId)

    override fun finishWorkoutIntent(workoutId: String): Intent =
        openWorkoutIntent(workoutId).putExtra(MainActivity.EXTRA_FINISH_WORKOUT, true)

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities++ }
            override fun onActivityStopped(activity: Activity) { startedActivities-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        // "Workout in progress" while there is one, whoever started it (this phone, the watch, an assistant).
        container.ongoingWorkout.start()
        // Without Play services there is no Data Layer and no watch to publish to (also the JVM tests).
        if (isWearableApiAvailable(this)) wearPublisher.start()
        // Sync: keep the periodic work while signed in, and sync whenever the app comes to the foreground.
        container.syncAccount.restoreSchedule()
        // Push: register this phone's FCM token or UnifiedPush endpoint with the server while signed in.
        container.push.start()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = container.syncAccount.requestSync()
        })
    }

    /**
     * WorkManager is initialised on demand with this configuration (the
     * default initializer is removed in the manifest), so nothing touches it
     * until the first sync is scheduled, and the tests can install their own.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    /** Overridden only by the test application of the flow tests. */
    /** The phone's container: Health Connect is the real one here, and only here (the watch has none). */
    protected open fun createContainer(): TungContainer = TungContainer(this, createHealthConnect = ::AndroidHealthConnectGateway)

    /**
     * Speech to text for the active workout's microphone (CLAUDE.md, "Voice"):
     * the phone's recogniser. Overridden by the flow tests' application with a
     * scripted fake; not a container seam, because nothing in `data/` hears.
     */
    open fun createSpeechInput(): SpeechInput = AndroidSpeechInput(this)

    /**
     * Sign in with Google for Settings (CLAUDE.md, "Sync", "Account"):
     * Credential Manager. Overridden by the flow tests' application with a
     * fake, like [createSpeechInput]; not a container seam, since `data/`
     * only ever sees the ID token.
     */
    open fun createGoogleSignIn(): GoogleSignIn = AndroidGoogleSignIn()
}
