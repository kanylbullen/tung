package io.github.kanylbullen.tung.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.intl.Locale
import io.github.kanylbullen.tung.auth.AppleSignInResult
import io.github.kanylbullen.tung.auth.GoogleSignInResult
import io.github.kanylbullen.tung.auth.appleIdToken
import io.github.kanylbullen.tung.data.Exercise
import io.github.kanylbullen.tung.ui.settings.Screenshot
import io.github.kanylbullen.tung.ui.workout.WorkoutPlatform
import io.github.kanylbullen.tung.voice.SpeechErrorKind
import io.github.kanylbullen.tung.voice.SpeechEvent
import io.github.kanylbullen.tung.voice.SpeechInput
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeCommaSeparatedText
import platform.UniformTypeIdentifiers.UTTypePlainText
import platform.UniformTypeIdentifiers.UTTypeText
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObject

/**
 * The iPhone's [AppPlatform]: the bundle's version, catalogue names from
 * [container]'s `core.json` in the app's language, a short message over the
 * screen instead of a toast, haptics, the notification permission, Sign in
 * with Apple, and the document picker for an import. Not there yet, each
 * saying so where it shows: speech (the mic answers "unavailable"), Google
 * sign-in, feedback screenshots and a health store (Settings leaves its row out).
 */
class IosPlatform(private val container: IosContainer) : AppPlatform {
    private val bundle get() = NSBundle.mainBundle

    override val versionName: String get() = bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "?"

    @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
    override val buildType: String get() = if (Platform.isDebugBinary) "debug" else "release"

    override val feedbackPlatform: String get() = "ios"
    override val feedbackVersion: String
        get() = "$versionName (${bundle.objectForInfoDictionaryKey("CFBundleVersion") as? String ?: "?"})"

    /** The phone's `BuildConfig.DEFAULT_SERVER_URL` and the web's `DEFAULT_SERVER_URL`. */
    override val defaultServerUrl: String get() = "https://tung.xuper.fun"

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** What [showMessage] said, for the overlay `MainViewController` draws over the app. */
    val messages: SharedFlow<String> = _messages

    /** A catalogue exercise by its key in the app's language (Swedish or English), else the name it was given. */
    override fun exerciseName(exercise: Exercise): String {
        val key = exercise.nameKey ?: return exercise.name
        val names = container.coreCatalog.firstOrNull { it.key == key }?.name ?: return exercise.name
        val language = if (Locale.current.language == "sv") "sv" else "en"
        return names[language] ?: names["en"] ?: exercise.name
    }

    override fun showMessage(text: String) {
        _messages.tryEmit(text)
    }

    /** iOS has no system back; navigation's own back buttons and the swipe are enough. */
    @Composable
    override fun BackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

    @Composable
    override fun rememberWorkoutPlatform(): WorkoutPlatform = remember {
        object : WorkoutPlatform {
            override val speech: SpeechInput = NoSpeech
            override fun showMessage(text: String) = this@IosPlatform.showMessage(text)
            override fun askForNotifications() {
                UNUserNotificationCenter.currentNotificationCenter()
                    .requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { _, _ -> }
            }
            // The recogniser answers "unavailable" at once, so there is nothing to ask for yet.
            override fun microphoneAllowed(): Boolean = true
            override fun askForMicrophone() = Unit
            override val microphoneAnswers: Flow<Boolean> = emptyFlow()
            override fun confirmHaptic() {
                UINotificationFeedbackGenerator().notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
            }
        }
    }

    @Composable
    override fun rememberGoogleIdToken(): suspend (clientId: String) -> GoogleSignInResult =
        remember { { _ -> GoogleSignInResult.Unavailable } }

    @Composable
    override fun rememberAppleIdToken(): (suspend () -> AppleSignInResult)? = remember { { appleIdToken() } }

    /**
     * The system document picker, for comma-separated and plain text; it hands
     * over a copy in the app's own temporary directory, so the source is that
     * file's URL and reading it needs no security scope.
     */
    @Composable
    override fun rememberImportFilePicker(onPicked: (source: String) -> Unit): () -> Unit {
        val latest by rememberUpdatedState(onPicked)
        val delegate = remember { DocumentPickerDelegate { url -> latest(url) } }
        return {
            val picker = UIDocumentPickerViewController(
                forOpeningContentTypes = listOf(UTTypeCommaSeparatedText, UTTypePlainText, UTTypeText),
                asCopy = true,
            )
            picker.delegate = delegate
            topViewController()?.presentViewController(picker, animated = true, completion = null)
        }
    }

    @OptIn(BetaInteropApi::class)
    override suspend fun readImportFile(source: String): String? = withContext(Dispatchers.Default) {
        val url = NSURL.URLWithString(source) ?: return@withContext null
        val data = NSData.dataWithContentsOfURL(url) ?: return@withContext null
        if (data.length.toLong() > MAX_IMPORT_BYTES) return@withContext null
        NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
    }

    /** Not yet on the iPhone: the button does nothing (the photo picker, PHPicker, comes later). */
    @Composable
    override fun rememberScreenshotPicker(room: Int, onPicked: suspend (List<Screenshot?>) -> Unit): () -> Unit = remember { {} }

    @Composable
    override fun healthSummary(): String? = null

    override val healthSettings: (@Composable (onBack: () -> Unit) -> Unit)? = null

    private companion object {
        /** The phone's limit: an export bigger than this is not a training log. */
        const val MAX_IMPORT_BYTES = 64L * 1024 * 1024
    }
}

/** No recogniser on the iPhone yet (SFSpeechRecognizer comes later): the mic says it is unavailable. */
private object NoSpeech : SpeechInput {
    override fun listenOnce(languageTag: String): Flow<SpeechEvent> = flowOf(SpeechEvent.Error(SpeechErrorKind.UNAVAILABLE))
}

/** Hands the picked document's URL to [onPicked]; kept alive by the screen that remembers it. */
private class DocumentPickerDelegate(private val onPicked: (String) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        (didPickDocumentsAtURLs.firstOrNull() as? NSURL)?.absoluteString?.let(onPicked)
    }
}

/** The view controller on top, to present a system sheet over the app. */
@OptIn(ExperimentalForeignApi::class)
private fun topViewController(): UIViewController? {
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}
