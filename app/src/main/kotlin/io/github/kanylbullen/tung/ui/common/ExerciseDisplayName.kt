package io.github.kanylbullen.tung.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * An exercise's displayed name for composables ([ExerciseNames], in `data/`);
 * recomposes with the configuration, so a language change shows at once.
 */
@Composable
fun exerciseDisplayName(nameKey: String?, name: String): String {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    return remember(nameKey, name, configuration) { ExerciseNames.resolve(context, nameKey, name) }
}
