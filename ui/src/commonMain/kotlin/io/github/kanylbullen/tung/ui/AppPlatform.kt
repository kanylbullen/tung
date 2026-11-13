package io.github.kanylbullen.tung.ui

import androidx.compose.runtime.Composable
import io.github.kanylbullen.tung.auth.AppleSignInResult
import io.github.kanylbullen.tung.auth.GoogleSignInResult
import io.github.kanylbullen.tung.data.Exercise
import io.github.kanylbullen.tung.ui.settings.Screenshot
import io.github.kanylbullen.tung.ui.workout.WorkoutPlatform

/**
 * What [TungNavRoot] needs from the platform it runs on, beyond the
 * container's [TungServices][io.github.kanylbullen.tung.data.TungServices]:
 * the build, how an exercise is named, the system's sheets and pickers, and
 * the health store. The phone implements it over Android (`app/`), the
 * iPhone over UIKit; a member a platform has no answer for says so (null).
 *
 * The `remember…` members are composable because what they return lives as
 * long as the screen that asked (an activity result launcher, a recogniser).
 */
interface AppPlatform {
    /** The app's version, for About and the settings list ("0.1.0"). */
    val versionName: String

    /** The build type, for About ("release", "dogfood", "debug"). */
    val buildType: String

    /** The platform key a feedback report carries (`android`, `ios`). */
    val feedbackPlatform: String

    /** The version line a feedback report carries ("0.1.0 (4371)"). */
    val feedbackVersion: String

    /** The server Account offers before the user names another. */
    val defaultServerUrl: String

    /** An exercise as shown: a catalogue exercise in the app's language, else the name it was given. */
    fun exerciseName(exercise: Exercise): String

    /** A short message that needs no answer (a toast on Android). */
    fun showMessage(text: String)

    /** Back from the system (Android's back gesture) while [enabled]; nothing where there is none. */
    @Composable
    fun BackHandler(enabled: Boolean, onBack: () -> Unit)

    /** The active workout's platform: speech, permissions, haptics. */
    @Composable
    fun rememberWorkoutPlatform(): WorkoutPlatform

    /** Google's account sheet for an ID token for a client id; a platform without it answers [GoogleSignInResult.Unavailable]. */
    @Composable
    fun rememberGoogleIdToken(): suspend (clientId: String) -> GoogleSignInResult

    /** Sign in with Apple's sheet; null where it does not exist (Android). */
    @Composable
    fun rememberAppleIdToken(): (suspend () -> AppleSignInResult)?

    /**
     * The system's document picker for an import; [onPicked] gets an opaque
     * source (a content URI on Android) that [readImportFile] reads.
     */
    @Composable
    fun rememberImportFilePicker(onPicked: (source: String) -> Unit): () -> Unit

    /** The text of a picked import [source], or null when it cannot be read or is too large. */
    suspend fun readImportFile(source: String): String?

    /**
     * The photo picker for feedback screenshots, offering at most [room]
     * (what the form has room for, so none is silently dropped); each picked
     * image is read and scaled, a null where one could not be.
     */
    @Composable
    fun rememberScreenshotPicker(room: Int, onPicked: suspend (List<Screenshot?>) -> Unit): () -> Unit

    /** The health row's line on the settings list (Health Connect on Android); null without a health store. */
    @Composable
    fun healthSummary(): String?

    /** The health settings screen; null where the platform has no health store, and Settings leaves its row out. */
    val healthSettings: (@Composable (onBack: () -> Unit) -> Unit)?

    /**
     * Whether a rest may end late with the app in the background (Android's
     * "Alarms & reminders", issue #215); null where the platform has no such
     * setting, and Settings → Training says nothing.
     */
    val exactAlarms: ExactAlarms? get() = null
}

/** The platform's exact-alarm permission, as Settings → Training shows it. */
interface ExactAlarms {
    /** True when the rest's alarm may fire on time in the background. Read again each time the screen resumes. */
    fun allowed(): Boolean

    /** Opens the system's page where the user allows it. */
    fun openSettings()
}
