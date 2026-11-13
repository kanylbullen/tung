package io.github.kanylbullen.tung.ui

import android.Manifest
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import io.github.kanylbullen.tung.BuildConfig
import io.github.kanylbullen.tung.TungApp
import io.github.kanylbullen.tung.auth.AppleSignInResult
import io.github.kanylbullen.tung.auth.GoogleSignInResult
import io.github.kanylbullen.tung.data.Exercise
import io.github.kanylbullen.tung.ui.common.displayName
import io.github.kanylbullen.tung.ui.settings.HealthConnectSettingsScreen
import io.github.kanylbullen.tung.ui.settings.Screenshot
import io.github.kanylbullen.tung.ui.settings.healthConnectSummary
import io.github.kanylbullen.tung.ui.settings.readScreenshot
import io.github.kanylbullen.tung.ui.workout.WorkoutPlatform
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler as ActivityBackHandler
import io.github.kanylbullen.tung.ui.importing.readImportFile as readImportDocument

/** The MIME types the system file picker offers: Jefit's export is a CSV, which phones label in several ways. */
val IMPORT_MIME_TYPES = arrayOf("text/*", "*/*")

/**
 * The phone's [AppPlatform]: [app]'s factories (the speech recogniser, Google
 * sign-in and the default server, which the flow tests replace), the build,
 * resources for exercise names, toasts, Android's permission sheets,
 * Credential Manager, the system file and photo pickers, and Health Connect.
 */
class AndroidPlatform(private val app: TungApp) : AppPlatform {
    override val versionName: String get() = BuildConfig.VERSION_NAME
    override val buildType: String get() = BuildConfig.BUILD_TYPE
    override val feedbackPlatform: String get() = "android"
    override val feedbackVersion: String get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    override val defaultServerUrl: String get() = app.defaultServerUrl

    /** "Alarms & reminders" (`SCHEDULE_EXACT_ALARM`), denied by default for new installs on Android 14+ (issue #215, `RestAlarm`). */
    override val exactAlarms: ExactAlarms = object : ExactAlarms {
        override fun allowed(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || app.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() != false

        override fun openSettings() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                app.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                // A device without the page: nothing to open.
            }
        }
    }

    override fun exerciseName(exercise: Exercise): String = exercise.displayName(app)

    override fun showMessage(text: String) {
        Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
    }

    @Composable
    override fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
        ActivityBackHandler(enabled = enabled, onBack = onBack)
    }

    /**
     * The speech recogniser [app] makes (a fake in the flow tests), Android's
     * permission sheets for notifications (Android 13+) and the microphone, a
     * toast and the view's haptics.
     */
    @Composable
    override fun rememberWorkoutPlatform(): WorkoutPlatform {
        val context = LocalContext.current
        val view = LocalView.current
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        val micAnswers = remember { Channel<Boolean>(Channel.BUFFERED) }
        val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micAnswers.trySend(granted)
        }
        return remember(context, view, notificationPermission, micPermission, micAnswers) {
            object : WorkoutPlatform {
                override val speech = app.createSpeechInput()
                override fun showMessage(text: String) {
                    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                }
                override fun askForNotifications() {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                override fun microphoneAllowed(): Boolean =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                override fun askForMicrophone() {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
                override val microphoneAnswers = micAnswers.receiveAsFlow()
                override fun confirmHaptic() {
                    view.performHapticFeedback(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK,
                    )
                }
            }
        }
    }

    /** Google's account sheet from Credential Manager over this activity. */
    @Composable
    override fun rememberGoogleIdToken(): suspend (clientId: String) -> GoogleSignInResult {
        val context: Context = LocalContext.current
        val google = remember { app.createGoogleSignIn() }
        return remember(context, google) { { clientId -> google.idToken(context, clientId) } }
    }

    @Composable
    override fun rememberAppleIdToken(): (suspend () -> AppleSignInResult)? = null

    /** The system file picker (`ACTION_OPEN_DOCUMENT`); the source is the document's content URI. */
    @Composable
    override fun rememberImportFilePicker(onPicked: (source: String) -> Unit): () -> Unit {
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onPicked(uri.toString())
        }
        return { picker.launch(IMPORT_MIME_TYPES) }
    }

    override suspend fun readImportFile(source: String): String? = readImportDocument(app, Uri.parse(source))

    /**
     * The photo picker, as many as there is [room] for: the multiple picker
     * needs at least two, so one left is the single picker. Each image is
     * read and scaled by [readScreenshot].
     */
    @Composable
    override fun rememberScreenshotPicker(room: Int, onPicked: suspend (List<Screenshot?>) -> Unit): () -> Unit {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(room.coerceAtLeast(2))) { uris ->
            if (uris.isNotEmpty()) scope.launch { onPicked(uris.map { readScreenshot(context, it) }) }
        }
        val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) scope.launch { onPicked(listOf(readScreenshot(context, uri))) }
        }
        return {
            val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            if (room >= 2) pickMany.launch(request) else pickOne.launch(request)
        }
    }

    @Composable
    override fun healthSummary(): String? = healthConnectSummary()

    override val healthSettings: @Composable (onBack: () -> Unit) -> Unit = { onBack -> HealthConnectSettingsScreen(onBack = onBack) }
}
