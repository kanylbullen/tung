package io.github.kanylbullen.tung.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.kanylbullen.tung.TungApp

/**
 * The phone's app: the shared [TungNavRoot] over this process's container
 * and the phone's [AndroidPlatform].
 *
 * @param openWorkout a workout to open on top of Home, set when a "Rest over"
 *   or the ongoing notification is tapped; [onWorkoutOpened] is called once it has been.
 */
@Composable
fun TungAppRoot(openWorkout: OpenWorkout? = null, onWorkoutOpened: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as TungApp
    val platform = remember(app) { AndroidPlatform(app) }
    TungNavRoot(app.container, platform, openWorkout, onWorkoutOpened)
}
