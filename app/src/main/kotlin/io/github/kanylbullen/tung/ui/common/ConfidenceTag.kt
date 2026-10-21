package io.github.kanylbullen.tung.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.ai.AiConfidence
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner

/**
 * How sure the assistant is of a proposal, as a small tag (issue #75): high
 * is an accent-filled chip, medium an accent outline, low a muted outline.
 * "No good match" has no tag; the caller shows muted text instead. The label
 * is the level ("High"); a screen reader hears "High confidence".
 */
@Composable
fun ConfidenceTag(confidence: AiConfidence, modifier: Modifier = Modifier) {
    val label = confidenceLabel(confidence)
    val description = stringResource(R.string.confidence_tag_description, label)
    val colors = Tung.colors
    val (fill, outline, text) = when (confidence) {
        AiConfidence.HIGH -> Triple(colors.accent, colors.accent, colors.onAccent)
        // The outline is a shape, so the light theme's #B98900 is enough; the label is text, so it takes accentText.
        AiConfidence.MEDIUM -> Triple(Color.Transparent, colors.accent, colors.accentText)
        AiConfidence.LOW -> Triple(Color.Transparent, colors.muted, colors.muted)
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = text,
        modifier = modifier
            .semantics { contentDescription = description }
            .background(fill, TungCorner)
            .border(1.dp, outline, TungCorner)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** "High", "Medium", "Low", in the app's language. */
@Composable
fun confidenceLabel(confidence: AiConfidence): String = stringResource(
    when (confidence) {
        AiConfidence.HIGH -> R.string.import_confidence_high
        AiConfidence.MEDIUM -> R.string.import_confidence_medium
        AiConfidence.LOW -> R.string.import_confidence_low
    },
)
