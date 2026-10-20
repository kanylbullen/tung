package io.github.kanylbullen.tung

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import io.github.kanylbullen.tung.ui.OpenWorkout
import io.github.kanylbullen.tung.ui.TungAppRoot
import io.github.kanylbullen.tung.ui.theme.TungTheme

class MainActivity : ComponentActivity() {

    /** A workout to open, from a tapped notification ("Rest over", the ongoing one); cleared once the app has navigated. */
    private val openWorkout = mutableStateOf<OpenWorkout?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only on a fresh start: a recreated activity already has its back stack.
        if (savedInstanceState == null) openWorkout.value = intent.workoutToOpen()
        addOnNewIntentListener { openWorkout.value = it.workoutToOpen() }
        setContent {
            TungTheme {
                TungAppRoot(
                    openWorkout = openWorkout.value,
                    onWorkoutOpened = { openWorkout.value = null },
                )
            }
        }
    }

    private fun Intent?.workoutToOpen(): OpenWorkout? {
        val id = this?.getStringExtra(EXTRA_OPEN_WORKOUT) ?: return null
        return OpenWorkout(id, askToFinish = getBooleanExtra(EXTRA_FINISH_WORKOUT, false))
    }

    companion object {
        const val EXTRA_OPEN_WORKOUT = "io.github.kanylbullen.tung.extra.OPEN_WORKOUT"

        /** With [EXTRA_OPEN_WORKOUT]: show the finish dialog (the ongoing notification's "Finish"). */
        const val EXTRA_FINISH_WORKOUT = "io.github.kanylbullen.tung.extra.FINISH_WORKOUT"
    }
}
