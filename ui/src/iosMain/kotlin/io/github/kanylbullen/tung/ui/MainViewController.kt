package io.github.kanylbullen.tung.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.kanylbullen.tung.data.IosContainer
import io.github.kanylbullen.tung.ui.common.LocalCatalogueName
import io.github.kanylbullen.tung.ui.resources.Res
import io.github.kanylbullen.tung.ui.resources.rest_notification_text
import io.github.kanylbullen.tung.ui.resources.rest_notification_title
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner
import io.github.kanylbullen.tung.ui.theme.TungTheme
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.getString
import platform.UIKit.UIViewController

/**
 * One container per process, as the phone's `TungApp` holds one. What it
 * needs from the app bundle comes from ui's resources: the catalogue
 * (`files/catalog/…`, iOS-only Compose Resources copied from `catalog/` at
 * build time) and the "Rest over" notification's text.
 */
@OptIn(ExperimentalResourceApi::class)
private val container by lazy {
    IosContainer(
        readCatalogue = { path -> Res.readBytes("files/catalog/$path").decodeToString() },
        restOverTitle = { getString(Res.string.rest_notification_title) },
        restOverText = { getString(Res.string.rest_notification_text) },
    )
}

/**
 * The iPhone app, as the Xcode app (iosApp/) embeds it: what Swift calls as
 * `MainViewControllerKt.MainViewController()`. The shared [TungNavRoot] over
 * the iPhone's [IosContainer] and [IosPlatform]; the catalogue's names for
 * every screen ([LocalCatalogueName]); a sync whenever the app comes to the
 * front, as the phone's; and [IosPlatform.showMessage]'s line over the
 * bottom of the screen, where the phone shows a toast.
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    val platform = remember { IosPlatform(container::catalogueName) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { container.onForeground() }
    CompositionLocalProvider(LocalCatalogueName provides container::catalogueName) {
        TungTheme {
            Box(Modifier.fillMaxSize().background(Tung.colors.background)) {
                TungNavRoot(container, platform)
                Messages(platform)
            }
        }
    }
}

/** One message to show; [id] tells two equal texts apart, so a repeat is shown its full time. */
private data class Message(val id: Long, val text: String)

/** The latest message for a few seconds, above the bottom bar, like a toast. */
@Composable
private fun Messages(platform: IosPlatform) {
    var message by remember { mutableStateOf<Message?>(null) }
    LaunchedEffect(platform) {
        var next = 0L
        platform.messages.collect { message = Message(next++, it) }
    }
    LaunchedEffect(message) {
        if (message != null) {
            delay(MESSAGE_MILLIS)
            message = null
        }
    }
    val text = message?.text ?: return
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars), contentAlignment = Alignment.BottomCenter) {
        Text(
            text,
            color = Tung.colors.text,
            modifier = Modifier
                .padding(start = 24.dp, end = 24.dp, bottom = 96.dp)
                .background(Tung.colors.raised, TungCorner)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** How long a message stays, Android's long toast. */
private const val MESSAGE_MILLIS = 3_500L
