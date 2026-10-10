package com.technlov.homecontroller

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.technlov.homecontroller.data.Weekend
import com.technlov.homecontroller.hub.HubService
import com.technlov.homecontroller.ui.HomeControllerApp
import com.technlov.homecontroller.ui.LocalWeekend
import com.technlov.homecontroller.ui.phoneRegion
import com.technlov.homecontroller.ui.theme.HomeTheme
import com.technlov.homecontroller.ui.theme.isDark

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val container = app
        // Keep the splash up until the saved data (theme included) has been read, so the first frame is right,
        // but never longer than a moment.
        val shownAt = SystemClock.uptimeMillis()
        splash.setKeepOnScreenCondition { !container.isLoaded && SystemClock.uptimeMillis() - shownAt < MAX_SPLASH_MS }
        enableEdgeToEdge()

        setContent {
            val settings by container.settings.collectAsState()
            val regionWeekend = remember { Weekend.forRegion(phoneRegion(this)) }
            val dark = settings.theme.isDark()
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            HomeTheme(dark = dark, dynamicColor = settings.dynamicColor) {
                CompositionLocalProvider(LocalWeekend provides (settings.weekend ?: regionWeekend)) {
                    HomeControllerApp()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.hub.appVisible.value = true
        HubService.start(this)
    }

    override fun onStop() {
        super.onStop()
        val container = app
        container.hub.appVisible.value = false
        if (!isChangingConfigurations && !container.settings.value.runInBackground) HubService.stop(this)
    }

    private companion object {
        const val MAX_SPLASH_MS = 1_500L
        // The default scrims from androidx.activity, used for three-button navigation.
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
