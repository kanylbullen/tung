package io.github.kanylbullen.tung.ui.workout

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.ui.common.exerciseDisplayName
import io.github.kanylbullen.tung.ui.common.localWeight
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner
import io.github.kanylbullen.tung.voice.SpeechErrorKind

/** How long an echo stays: long enough to read at a glance; longer when it offers the assistant. */
const val VOICE_ECHO_MILLIS = 3_000L
const val VOICE_ECHO_WITH_ACTION_MILLIS = 8_000L

/**
 * The round mic button left of Log set, as tall as it: accent outline, filled
 * while listening. A tap hears one utterance (or stops listening); a long
 * press turns on hands-free mode.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MicButton(active: Boolean, onTap: () -> Unit, onLongPress: () -> Unit) {
    val description = stringResource(R.string.voice_mic)
    val state = stringResource(if (active) R.string.voice_mic_listening else R.string.voice_mic_idle)
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (active) Tung.colors.accent else Tung.colors.background)
            .border(2.dp, Tung.colors.accent, CircleShape)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = stringResource(if (active) R.string.voice_mic_stop else R.string.voice_mic_tap),
                onLongClickLabel = stringResource(R.string.voice_hands_free_start),
                onLongClick = onLongPress,
                onClick = onTap,
            )
            .semantics {
                contentDescription = description
                stateDescription = state
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_mic),
            contentDescription = null,
            tint = if (active) Tung.colors.onAccent else Tung.colors.accentText,
        )
    }
}

/** Shown as long as hands-free mode is on; a tap turns it off. */
@Composable
fun HandsFreeChip(onStop: () -> Unit) {
    val description = stringResource(R.string.voice_hands_free_description)
    val stop = stringResource(R.string.voice_hands_free_stop)
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(TungCorner)
            .background(Tung.colors.raised)
            .border(1.dp, Tung.colors.accent, TungCorner)
            .clickable(onClick = onStop)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(label = stop) {
                    onStop()
                    true
                }
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Tung.colors.accent))
        Text(stringResource(R.string.voice_hands_free_chip), style = MaterialTheme.typography.labelLarge, color = Tung.colors.text)
    }
}

/** One line above the buttons: what was understood, or why not, with "Ask the assistant" when it can help. */
@Composable
fun VoiceEchoLine(text: String, muted: Boolean, actionLabel: String?, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (muted) Tung.colors.muted else Tung.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (actionLabel != null) {
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp).padding(start = 8.dp)) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge, color = Tung.colors.accentText)
            }
        }
    }
}

/** What [echo] says on screen; null for nothing (the finish dialog speaks for itself). */
@Composable
fun voiceEchoText(echo: VoiceEcho): String? {
    @Composable
    fun reps(reps: Int) = if (reps > 0) reps.toString() else stringResource(R.string.workout_reps_empty)

    return when (echo) {
        is VoiceEcho.Values -> stringResource(R.string.voice_echo_values, localWeight(echo.set.weightKg), reps(echo.set.reps))
        is VoiceEcho.Logged -> stringResource(R.string.voice_echo_logged, localWeight(echo.set.weightKg), reps(echo.set.reps))
        is VoiceEcho.Next -> stringResource(R.string.voice_echo_next, exerciseDisplayName(echo.nameKey, echo.name), echo.setNumber)
        is VoiceEcho.SetAdded -> stringResource(R.string.voice_echo_set_added, exerciseDisplayName(echo.nameKey, echo.name))
        VoiceEcho.RestStarted -> stringResource(R.string.voice_echo_rest)
        VoiceEcho.FinishAsked -> null
        is VoiceEcho.Problem -> when (echo.problem) {
            VoiceProblem.NO_CURRENT_SET -> stringResource(R.string.voice_echo_no_set)
            VoiceProblem.NO_PREVIOUS_SET -> stringResource(R.string.voice_echo_no_previous)
            VoiceProblem.NO_NEXT_SET -> stringResource(R.string.voice_echo_no_next)
            VoiceProblem.REST_OFF -> stringResource(R.string.voice_echo_rest_off, exerciseDisplayName(echo.nameKey, echo.name.orEmpty()))
        }
        is VoiceEcho.NotUnderstood -> stringResource(R.string.voice_echo_not_understood, echo.text)
        is VoiceEcho.Failed -> stringResource(
            when (echo.kind) {
                SpeechErrorKind.NO_SPEECH -> R.string.voice_echo_heard_nothing
                SpeechErrorKind.UNAVAILABLE -> R.string.voice_echo_unavailable
                SpeechErrorKind.PERMISSION -> R.string.voice_echo_permission
                SpeechErrorKind.NETWORK -> R.string.voice_echo_network
                SpeechErrorKind.OTHER -> R.string.voice_echo_failed
            },
        )
    }
}
