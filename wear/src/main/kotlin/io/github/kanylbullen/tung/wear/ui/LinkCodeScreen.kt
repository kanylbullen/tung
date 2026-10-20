package io.github.kanylbullen.tung.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
 */
@Composable
fun LinkCodeScreen(link: LinkUi, onDigit: (Char) -> Unit, onDelete: () -> Unit, onSubmit: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = link.error?.let { stringResource(it) }
                    ?: link.server?.let { stringResource(R.string.link_title_server, serverLabel(it)) }
                    ?: stringResource(R.string.link_title),
                color = if (link.error != null) TungColors.accent else TungColors.muted,
                style = MaterialTheme.typography.caption2,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
            val digits = link.input.digits
            val blank = stringResource(R.string.link_digit_blank).single()
            val shown = digits.padEnd(LINK_CODE_LENGTH, blank).chunked(3).joinToString(" ")
            val description = stringResource(R.string.link_digits_description, digits.length)
            Box(Modifier.size(width = 140.dp, height = 28.dp), contentAlignment = Alignment.Center) {
                if (link.busy) {
                    CircularProgressIndicator(indicatorColor = TungColors.accent, modifier = Modifier.size(24.dp))
                } else {
                    Text(
                        text = shown,
                        style = numberStyle(24.sp),
                        color = TungColors.text,
                        modifier = Modifier.semantics { contentDescription = description },
                    )
                }
            }
            KEYS.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    row.forEach { key -> Key(key, link, onDigit, onDelete, onSubmit) }
                }
            }
        }
    }
}

@Composable
private fun Key(key: Char, link: LinkUi, onDigit: (Char) -> Unit, onDelete: () -> Unit, onSubmit: () -> Unit) {
    val modifier = Modifier.size(width = 42.dp, height = 26.dp)
    when (key) {
        DELETE -> {
            val description = stringResource(R.string.link_delete)
            Button(
                onClick = onDelete,
                enabled = !link.busy && link.input.digits.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(backgroundColor = TungColors.background, contentColor = TungColors.muted),
                modifier = modifier.semantics { contentDescription = description },
            ) { Text(stringResource(R.string.link_delete_symbol), fontSize = 15.sp) }
        }
        SUBMIT -> {
            val description = stringResource(R.string.link_submit)
            Button(
                onClick = onSubmit,
                enabled = !link.busy && link.input.isComplete,
                colors = ButtonDefaults.buttonColors(backgroundColor = TungColors.accent, contentColor = TungColors.background),
                modifier = modifier.semantics { contentDescription = description },
            ) { Text(stringResource(R.string.link_submit_symbol), fontSize = 15.sp) }
        }
        else -> Button(
            onClick = { onDigit(key) },
            enabled = !link.busy,
            colors = ButtonDefaults.buttonColors(backgroundColor = TungColors.raised, contentColor = TungColors.text),
            modifier = modifier,
        ) { Text(key.toString(), style = numberStyle(17.sp)) }
    }
}
