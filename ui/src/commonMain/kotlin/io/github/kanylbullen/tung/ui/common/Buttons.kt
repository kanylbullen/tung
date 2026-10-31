package io.github.kanylbullen.tung.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner

/** The one loud button. Accent fill, 12dp corners, no elevation. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = TungCorner,
        colors = ButtonDefaults.buttonColors(
            containerColor = Tung.colors.accent,
            contentColor = Tung.colors.onAccent,
            disabledContainerColor = Tung.colors.border,
            disabledContentColor = Tung.colors.muted,
        ),
        elevation = null,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A quieter full-width button on the raised surface. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = TungCorner,
        colors = ButtonDefaults.buttonColors(
            containerColor = Tung.colors.raised,
            contentColor = Tung.colors.text,
            disabledContainerColor = Tung.colors.raised,
            disabledContentColor = Tung.colors.muted,
        ),
        elevation = null,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Text field colours on the raised surface, shared by every dialog with a text field. */
@Composable
fun tungFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Tung.colors.raised,
    unfocusedContainerColor = Tung.colors.raised,
    focusedBorderColor = Tung.colors.muted,
    unfocusedBorderColor = Tung.colors.border,
    focusedTextColor = Tung.colors.text,
    unfocusedTextColor = Tung.colors.text,
    cursorColor = Tung.colors.accent,
    focusedLeadingIconColor = Tung.colors.muted,
    unfocusedLeadingIconColor = Tung.colors.muted,
    focusedTrailingIconColor = Tung.colors.muted,
    unfocusedTrailingIconColor = Tung.colors.muted,
    focusedPlaceholderColor = Tung.colors.muted,
    unfocusedPlaceholderColor = Tung.colors.muted,
    focusedLabelColor = Tung.colors.muted,
    unfocusedLabelColor = Tung.colors.muted,
)
