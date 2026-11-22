package io.github.kanylbullen.tung.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import io.github.kanylbullen.tung.wear.R

/**
 * "Finish workout?" on the watch: the title, what finishing removes, and two
 * round buttons side by side, ✕ to keep training and ✓ to finish (Wear's
 * confirmation layout). Drawn on black and laid out by the screen's diameter,
 * so at the default font size the whole of it lies on the circle of a small
 * round watch (192 dp) with nothing to scroll; a larger font scrolls instead
 * of being cut. Before #263 this was an `Alert` with two full-width chips,
 * which was taller than the screen: Play saw the title cut at the top and
 * "Keep training" at the bottom, on a grey surface.
 */
@Composable
fun FinishConfirmation(onFinish: () -> Unit, onKeepTraining: () -> Unit) {
    val diameter = screenDiameter()
    Box(Modifier.fillMaxSize().background(TungColors.black).verticalScroll(rememberScrollState())) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = diameter)
                .padding(horizontal = diameter * 0.12f, vertical = diameter * 0.1f),
            verticalArrangement = Arrangement.spacedBy(diameter * 0.03f, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.finish_title),
                color = TungColors.text,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.title3,
            )
            Text(
                text = stringResource(R.string.finish_body),
                color = TungColors.muted,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.caption1,
                modifier = Modifier.padding(horizontal = diameter * 0.02f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(diameter * 0.06f)) {
                val keep = stringResource(R.string.keep_training)
                Button(
                    onClick = onKeepTraining,
                    colors = ButtonDefaults.buttonColors(backgroundColor = TungColors.raised, contentColor = TungColors.text),
                    modifier = Modifier.semantics { contentDescription = keep },
                ) { Icon(painterResource(R.drawable.ic_close), contentDescription = null) }
                val finish = stringResource(R.string.finish_confirm)
                Button(
                    onClick = onFinish,
                    colors = ButtonDefaults.buttonColors(backgroundColor = TungColors.accent, contentColor = TungColors.background),
                    modifier = Modifier.semantics { contentDescription = finish },
                ) { Icon(painterResource(R.drawable.ic_check), contentDescription = null) }
            }
        }
    }
}
