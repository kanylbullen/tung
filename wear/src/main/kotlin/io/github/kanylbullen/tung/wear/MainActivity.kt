package io.github.kanylbullen.tung.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.kanylbullen.tung.data.TungHost
import io.github.kanylbullen.tung.wear.ui.TungWearTheme
import io.github.kanylbullen.tung.wear.ui.WatchApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val watch: WatchViewModel by viewModels()
    private val account: AccountViewModel by viewModels()

    /** Asked once, the first time a workout is on the watch: the Ongoing Activity and "Rest over" need it. Denied is fine. */
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TungWearTheme {
                WatchApp(watch, account)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    watch.ui.first { it.state?.isActive == true }
                    val granted = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                    val restTimer = (application as TungHost).container.restTimer
                    if (!granted && restTimer.firstTimeAskingForNotifications()) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
    }
}
