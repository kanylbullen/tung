package io.github.kanylbullen.tung.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.BuildConfig
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.sync.FeedbackOutcome
import io.github.kanylbullen.tung.ui.common.PrimaryButton
import io.github.kanylbullen.tung.ui.common.SecondaryButton
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.factoryOf
import io.github.kanylbullen.tung.ui.common.tungFieldColors
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner
import kotlinx.coroutines.launch

/**
 * Settings → Feedback: Bug or Idea, the words, up to three screenshots from
 * the photo picker, and Send. Needs an account (the feedback names it, and
 * the server's admins read it); signed out, a hint and the way to Account.
 */
@Composable
fun FeedbackScreen(onBack: () -> Unit, onOpenAccount: () -> Unit) {
    val container = appContainer()
    val vm: FeedbackViewModel = viewModel(
        factory = factoryOf { FeedbackViewModel(container.syncAccount, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val form = state.form
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_FEEDBACK_SCREENSHOTS)) { uris ->
        if (uris.isNotEmpty()) scope.launch { vm.addScreenshots(uris.map { readScreenshot(context, it) }) }
    }

    SettingsPage(title = stringResource(R.string.settings_group_feedback), onBack = onBack) {
        when (state.signedIn) {
            null -> Unit
            false -> {
                Text(stringResource(R.string.feedback_signed_out), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
                SecondaryButton(text = stringResource(R.string.settings_devices_open_account), onClick = onOpenAccount)
            }
            true -> {
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KindChip(stringResource(R.string.feedback_bug), form.kind == FeedbackKind.BUG) { vm.setKind(FeedbackKind.BUG) }
                    KindChip(stringResource(R.string.feedback_idea), form.kind == FeedbackKind.IDEA) { vm.setKind(FeedbackKind.IDEA) }
                }
                OutlinedTextField(
                    value = form.message,
                    onValueChange = vm::setMessage,
                    label = { Text(stringResource(R.string.feedback_message)) },
                    minLines = 5,
                    shape = TungCorner,
                    colors = tungFieldColors(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                )
                if (form.screenshots.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        form.screenshots.forEachIndexed { index, shot ->
                            Box(Modifier.size(96.dp).clip(TungCorner).border(1.dp, Tung.colors.border, TungCorner)) {
                                Image(
                                    bitmap = shot.thumbnail,
                                    contentDescription = stringResource(R.string.feedback_screenshot, index + 1),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(96.dp),
                                )
                                IconButton(
                                    onClick = { vm.removeScreenshot(index) },
                                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(32.dp).background(Tung.colors.background, CircleShape),
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.feedback_remove_screenshot, index + 1))
                                }
                            }
                        }
                    }
                    Text(stringResource(R.string.feedback_screenshot_notice), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
                }
                SecondaryButton(
                    text = stringResource(R.string.feedback_add_screenshot),
                    enabled = form.canAddScreenshot && !form.sending,
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
                PrimaryButton(
                    text = stringResource(if (form.sending) R.string.feedback_sending else R.string.feedback_send),
                    enabled = form.canSend,
                    onClick = vm::send,
                )
                val message = when {
                    form.unreadable -> R.string.feedback_unreadable
                    else -> form.outcome?.let(::outcomeText)
                }
                message?.let {
                    Text(
                        stringResource(it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (form.outcome == FeedbackOutcome.SENT && !form.unreadable) Tung.colors.text else Tung.colors.accentText,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}

private fun outcomeText(outcome: FeedbackOutcome): Int = when (outcome) {
    FeedbackOutcome.SENT -> R.string.feedback_sent
    FeedbackOutcome.TOO_LARGE -> R.string.feedback_too_large
    FeedbackOutcome.TOO_MANY_TODAY -> R.string.feedback_too_many
    FeedbackOutcome.NOT_SIGNED_IN, FeedbackOutcome.SIGNED_OUT -> R.string.feedback_signed_out
    FeedbackOutcome.NOT_A_SERVER -> R.string.feedback_not_supported
    FeedbackOutcome.NETWORK, FeedbackOutcome.SERVER -> R.string.feedback_failed
}

@Composable
private fun KindChip(text: String, on: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = on,
        onClick = onClick,
        label = { Text(text) },
        shape = TungCorner,
        modifier = Modifier.semantics { role = Role.RadioButton },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Tung.colors.raised,
            labelColor = Tung.colors.text,
            selectedContainerColor = Tung.colors.text,
            selectedLabelColor = Tung.colors.background,
        ),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = on, borderColor = Tung.colors.muted, selectedBorderColor = Tung.colors.text),
    )
}
