package io.github.kanylbullen.tung.ui.workout

import io.github.kanylbullen.tung.voice.SpeechInput
import kotlinx.coroutines.flow.Flow

/**
 * What the workout screen needs from the platform it runs on, beyond its
 * ViewModel: speech, permissions, a message that outlives the screen and a
 * haptic. The phone's is built in app's `ActiveWorkoutRoute` over Android's
 * permission launchers, a toast and the view's haptics.
 */
interface WorkoutPlatform {
    /** The recogniser the mic listens with. */
    val speech: SpeechInput

    /** Shows a short message that outlives the screen, such as "Workout not saved": a toast on Android. */
    fun showMessage(text: String)

    /** Asks for permission to post notifications, if the platform needs one and it is not granted; denied is fine. */
    fun askForNotifications()

    /** Whether the microphone may be used now. */
    fun microphoneAllowed(): Boolean

    /** Asks for the microphone; the answer arrives on [microphoneAnswers]. */
    fun askForMicrophone()

    /**
     * Each answer to [askForMicrophone], buffered until the screen reads it, so
     * one that arrives after the screen was recreated (rotated while the
     * permission sheet was up) is still heard.
     */
    val microphoneAnswers: Flow<Boolean>

    /** One firm haptic, for a spoken intent that changed something. */
    fun confirmHaptic()
}
