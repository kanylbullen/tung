package io.github.kanylbullen.tung.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.wear.ui.TungWearTheme
import io.github.kanylbullen.tung.wear.ui.WatchApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TungWearTheme {
                WatchApp(viewModel<WatchViewModel>(), viewModel<AccountViewModel>())
            }
        }
    }
}
