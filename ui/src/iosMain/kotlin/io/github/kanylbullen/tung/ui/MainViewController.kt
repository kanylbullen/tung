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
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner
import io.github.kanylbullen.tung.ui.theme.TungTheme
import kotlinx.coroutines.delay
import platform.UIKit.UIViewController

/** One container per process, as the phone's `TungApp` holds one. */
private val container by lazy { IosContainer() }

/**
 * The iPhone app, as the Xcode app (iosApp/) embeds it: what Swift calls as
 * `MainViewControllerKt.MainViewController()`. The shared [TungNavRoot] over
 * the iPhone's [IosContainer] and [IosPlatform]; a sync whenever the app
 * comes to the front, as the phone's; and [IosPlatform.showMessage]'s line
 * over the bottom of the screen, where the phone shows a toast.
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    val platform = remember { IosPlatform(container) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { container.onForeground() }
    TungTheme {
        Box(Modifier.fillMaxSize().background(Tung.colors.background)) {
            TungNavRoot(container, platform)
            Messages(platform)
        }
    }
}

/** The latest message for a few seconds, above the bottom bar, like a toast. */
@Composable
private fun Messages(platform: IosPlatform) {
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(platform) {
        platform.messages.collect { message = it }
    }
    LaunchedEffect(message) {
        if (message != null) {
            delay(MESSAGE_MILLIS)
            message = null
        }
    }
    val text = message ?: return
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
