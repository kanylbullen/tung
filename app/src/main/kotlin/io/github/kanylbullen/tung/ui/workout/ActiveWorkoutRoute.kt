package io.github.kanylbullen.tung.ui.workout

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.kanylbullen.tung.TungApp
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.progress.ProgressSheetRoute
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The active workout on the phone: [ActiveWorkoutScreen] (in `ui/`) with its
 * ViewModel from the container, and the phone's [WorkoutPlatform]: the speech
 * recogniser `TungApp` makes (a fake in the flow tests), Android's permission
 * sheets for notifications (Android 13+) and the microphone, a toast and the
 * view's haptics. The progress sheet is the phone's `ProgressSheet`.
 */
@Composable
fun ActiveWorkoutRoute(
    workoutId: String,
    onBack: () -> Unit,
    askToFinish: Boolean,
    onFinishAsked: () -> Unit,
    onAddExercise: () -> Unit,
    onFinished: (workoutId: String, saved: Boolean) -> Unit,
    onDiscarded: () -> Unit,
    onAskAssistant: (String) -> Unit,
) {
    val container = appContainer()
    val vm: ActiveWorkoutViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                ActiveWorkoutViewModel(
                    workoutId = workoutId,
                    repo = container.workoutRepository,
                    commands = container.commands,
                    restTimer = container.restTimer,
                    savedState = createSavedStateHandle(),
                    pastWorkouts = container.pastWorkouts,
                )
            }
        }
    )
    val assistantConfigured by remember { container.aiSettings.config.map { it.configured } }.collectAsStateWithLifecycle(false)
    val context = LocalContext.current
    val view = LocalView.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val micAnswers = remember { Channel<Boolean>(Channel.BUFFERED) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micAnswers.trySend(granted)
    }
    val platform = remember(context, view, notificationPermission, micPermission, micAnswers) {
        object : WorkoutPlatform {
            override val speech = (context.applicationContext as TungApp).createSpeechInput()

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
    ActiveWorkoutScreen(
        vm = vm,
        platform = platform,
        assistantConfigured = assistantConfigured,
        progressSheet = { exerciseId, exerciseName, proposed, onDismiss -> ProgressSheetRoute(exerciseId, exerciseName, proposed, onDismiss) },
        onBack = onBack,
        askToFinish = askToFinish,
        onFinishAsked = onFinishAsked,
        onAddExercise = onAddExercise,
        onFinished = onFinished,
        onDiscarded = onDiscarded,
        onAskAssistant = onAskAssistant,
    )
}
