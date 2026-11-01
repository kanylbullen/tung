package io.github.kanylbullen.tung.ui.common

import io.github.kanylbullen.tung.ui.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.kanylbullen.tung.ui.theme.Tung
import androidx.compose.material3.MaterialTheme

/**
 * A labelled big number, used in summary and detail. Value and label are one
 * semantics node, so a screen reader says "640, Volume, kg" in one go.
 */
@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.semantics(mergeDescendants = true) {}) {
        Text(value, style = Tung.numbers.stat, color = Tung.colors.text, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
    }
}

@Composable
fun StatRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}
