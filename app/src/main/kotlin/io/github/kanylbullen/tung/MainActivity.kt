package io.github.kanylbullen.tung

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.kanylbullen.tung.ui.TungAppRoot
import io.github.kanylbullen.tung.ui.theme.TungTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TungTheme {
                TungAppRoot()
            }
        }
    }
}
