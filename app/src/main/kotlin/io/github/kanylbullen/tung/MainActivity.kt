package io.github.kanylbullen.tung

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import io.github.kanylbullen.tung.ui.TungAppRoot
import io.github.kanylbullen.tung.ui.theme.TungTheme

class MainActivity : ComponentActivity() {

    /** A workout to open, from a tapped "Rest over" notification; cleared once the app has navigated. */
    private val openWorkout = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only on a fresh start: a recreated activity already has its back stack.
        if (savedInstanceState == null) openWorkout.value = intent.workoutToOpen()
        addOnNewIntentListener { openWorkout.value = it.workoutToOpen() }
        setContent {
            TungTheme {
                TungAppRoot(
                    openWorkoutId = openWorkout.value,
                    onWorkoutOpened = { openWorkout.value = null },
                )
            }
        }
    }

    private fun Intent?.workoutToOpen(): String? = this?.getStringExtra(EXTRA_OPEN_WORKOUT)

    companion object {
        const val EXTRA_OPEN_WORKOUT = "io.github.kanylbullen.tung.extra.OPEN_WORKOUT"
    }
}
