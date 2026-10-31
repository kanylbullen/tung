package io.github.kanylbullen.tung.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import io.github.kanylbullen.tung.wear.LINK_CODE_LENGTH
import io.github.kanylbullen.tung.wear.LinkUi
import io.github.kanylbullen.tung.wear.R

/** The number pad's keys, row by row; [DELETE] and [SUBMIT] are the bottom corners. */
private val KEYS = listOf("123", "456", "789", "${DELETE}0$SUBMIT")
private const val DELETE = '<'
private const val SUBMIT = '>'

/**
 * The six-digit code from the phone (Settings, "Link a watch"), typed on a
 * number pad as large as a round screen allows. The sixth digit sends it.
 *
 * Every size is a share of the screen's diameter, so the pad's corner keys
 * stay on the circle from a 192 dp watch to a 227 dp one: the widest rows sit
 * low, where the circle narrows, so the keys are wide and short. The title
 * always takes two lines' height, so an error does not move the pad.
 */
@Composable
fun LinkCodeScreen(link: LinkUi, onDigit: (Char) -> Unit, onDelete: () -> Unit, onSubmit: () -> Unit) {
    val diameter = screenDiameter()
    val gap = diameter * 0.012f
    val key = DpSize(width = diameter * 0.22f, height = diameter * 0.105f)
    // The keys' labels are sized with the keys, not by the font scale, or a larger font would not fit the key.
    val label = with(LocalDensity.current) { (diameter * 0.075f).toSp() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(gap)) {
            Box(Modifier.width(diameter * 0.6f).heightIn(min = 28.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = link.error?.let { stringResource(it) }
                        ?: link.server?.let { stringResource(R.string.link_title_server, serverLabel(it)) }
                        ?: stringResource(R.string.link_title),
                    color = if (link.error != null) TungColors.accent else TungColors.muted,
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val digits = link.input.digits
            val blank = stringResource(R.string.link_digit_blank).single()
            val shown = digits.padEnd(LINK_CODE_LENGTH, blank).chunked(3).joinToString(" ")
            val description = stringResource(R.string.link_digits_description, digits.length)
            Box(Modifier.size(width = diameter * 0.6f, height = diameter * 0.12f), contentAlignment = Alignment.Center) {
                if (link.busy) {
                    CircularProgressIndicator(indicatorColor = TungColors.accent, modifier = Modifier.size(diameter * 0.1f))
                } else {
                    Text(
                        text = shown,
                        style = numberStyle(22.sp),
                        color = TungColors.text,
                        modifier = Modifier.semantics { contentDescription = description },
                    )
                }
            }
            KEYS.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { char -> Key(char, key, label, link, onDigit, onDelete, onSubmit) }
                }
            }
        }
    }
}

@Composable
private fun Key(key: Char, size: DpSize, label: TextUnit, link: LinkUi, onDigit: (Char) -> Unit, onDelete: () -> Unit, onSubmit: () -> Unit) {
    val modifier = Modifier.size(size)
    when (key) {
        DELETE -> {
            val description = stringResource(R.string.link_delete)
            Button(
                onClick = onDelete,
                enabled = !link.busy && link.input.digits.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(backgroundColor = TungColors.background, contentColor = TungColors.muted),
                modifier = modifier.semantics { contentDescription = description },
            ) { Text(stringResource(R.string.link_delete_symbol), fontSize = label * 0.9f) }
        }
        SUBMIT -> {
            val description = stringResource(R.string.link_submit)
            Button(
                onClick = onSubmit,
                enabled = !link.busy && link.input.isComplete,
                colors = ButtonDefaults.buttonColors(backgroundColor = TungColors.accent, contentColor = TungColors.background),
                modifier = modifier.semantics { contentDescription = description },
            ) { Text(stringResource(R.string.link_submit_symbol), fontSize = label * 0.9f) }
        }
        else -> Button(
            onClick = { onDigit(key) },
            enabled = !link.busy,
            colors = ButtonDefaults.buttonColors(backgroundColor = TungColors.raised, contentColor = TungColors.text),
            modifier = modifier,
        ) { Text(key.toString(), style = numberStyle(label)) }
    }
}
