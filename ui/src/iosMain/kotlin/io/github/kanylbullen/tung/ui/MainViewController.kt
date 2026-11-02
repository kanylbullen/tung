package io.github.kanylbullen.tung.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import io.github.kanylbullen.tung.ui.resources.Res
import io.github.kanylbullen.tung.ui.resources.home_wordmark
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungTheme
import org.jetbrains.compose.resources.stringResource
import platform.UIKit.UIViewController

/**
 * The iPhone app's one screen, as the Xcode app (iosApp/) embeds it: what
 * Swift calls as `MainViewControllerKt.MainViewController()`. For now the
 * theme, the font and the strings on a plain page, which is enough to prove
 * the framework links and draws; the navigation root and the iOS container
 * replace it (CLAUDE.md, "Layers", `ui/`).
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    TungTheme {
        Box(Modifier.fillMaxSize().background(Tung.colors.background), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.home_wordmark), style = Tung.numbers.wordmark, color = Tung.colors.text)
        }
    }
}
