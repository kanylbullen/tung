package io.github.kanylbullen.jarn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.kanylbullen.jarn.ui.JarnAppRoot
import io.github.kanylbullen.jarn.ui.theme.JarnTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            JarnTheme {
                JarnAppRoot()
            }
        }
    }
}
