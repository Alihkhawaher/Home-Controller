package com.technlov.homecontroller

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.technlov.home.core.ConnectorRegistry
import com.technlov.home.core.DefaultAutomationEngine
import com.technlov.home.core.EventLog
import com.technlov.home.core.error
import com.technlov.home.core.info
import com.technlov.homecontroller.data.AppSettings
import com.technlov.homecontroller.data.HomeDataStore
import com.technlov.homecontroller.data.HomeRepository
import com.technlov.homecontroller.diag.AppEventLog
import com.technlov.homecontroller.diag.LogSource
import com.technlov.homecontroller.diag.RollingLogFile
import com.technlov.homecontroller.hub.Hub
import com.technlov.homecontroller.hub.HubNotifications
import com.technlov.homecontroller.net.LocalNetworkProvider
import com.technlov.homecontroller.net.WifiScanner
import com.technlov.homecontroller.pairing.AndroidPairingPlatform
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class HomeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        logCrashes(container.eventLog)
        container.eventLog.info(
            LogSource.APP,
            "App started: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE}), " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
        container.notifications.createChannels()
    }

    /** An uncaught exception is written to the log (synchronously) before Android's own handler ends the app. */
    private fun logCrashes(log: AppEventLog) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                log.logNow(EventLog.Level.ERROR, LogSource.CRASH, "Uncaught exception in thread \"${thread.name}\"", error)
            }
            previous?.uncaughtException(thread, error)
        }
    }
}

/** The app's single instances: diagnostics log, connectors, saved data, network, hub, pairing. */
class AppContainer(context: Context) {
    /** Diagnostics for the app and every connector. Read back from disk first, so it spans restarts. */
    val eventLog = AppEventLog(RollingLogFile(File(context.filesDir, "logs"))).also { it.loadSaved() }

    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            Log.e("HomeApp", "Background error", e)
            eventLog.error(LogSource.APP, "Background error", e)
        },
    )
    val registry: ConnectorRegistry = Connectors.registry(eventLog)
    val repository = HomeRepository(HomeDataStore.create(context, CoroutineScope(SupervisorJob() + Dispatchers.IO)))
    val network = LocalNetworkProvider(context, eventLog)
    val wifiScanner = WifiScanner(context)
    val pairingPlatform = AndroidPairingPlatform(context, network, wifiScanner, eventLog)
    val notifications = HubNotifications(context)
    val hub = Hub(
        registry = registry,
        repository = repository,
        engine = DefaultAutomationEngine(),
        events = notifications,
        beforeNetworkUse = { network.prepare() },
        log = eventLog,
    )

    /** Null until the saved data has been read once (the splash screen waits for it). */
    private val loadedSettings: StateFlow<AppSettings?> =
        repository.data.map<_, AppSettings?> { it.settings }.stateIn(scope, SharingStarted.Eagerly, null)

    val isLoaded: Boolean get() = loadedSettings.value != null
    val settings: StateFlow<AppSettings> =
        repository.data.map { it.settings }.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    init {
        // "Verbose logging" in Diagnostics: connectors then also record every frame to and from devices.
        scope.launch {
            settings.map { it.verboseLogging }.distinctUntilChanged().collect { on ->
                if (on != eventLog.verbose) eventLog.info(LogSource.APP, "Verbose logging ${if (on) "on" else "off"}")
                eventLog.verbose = on
            }
        }
    }
}

val Context.app: AppContainer get() = (applicationContext as HomeApp).container
