package com.technlov.homecontroller

import android.app.Application
import android.content.Context
import android.util.Log
import com.technlov.home.core.ConnectorRegistry
import com.technlov.home.core.DefaultAutomationEngine
import com.technlov.homecontroller.data.AppSettings
import com.technlov.homecontroller.data.HomeDataStore
import com.technlov.homecontroller.data.HomeRepository
import com.technlov.homecontroller.hub.Hub
import com.technlov.homecontroller.hub.HubNotifications
import com.technlov.homecontroller.net.LocalNetworkProvider
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class HomeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifications.createChannels()
    }
}

/** The app's single instances: connectors, saved data, network, hub. */
class AppContainer(context: Context) {
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("HomeApp", "Background error", e) },
    )
    val registry: ConnectorRegistry = Connectors.registry()
    val repository = HomeRepository(HomeDataStore.create(context, CoroutineScope(SupervisorJob() + Dispatchers.IO)))
    val network = LocalNetworkProvider(context)
    val notifications = HubNotifications(context)
    val hub = Hub(
        registry = registry,
        repository = repository,
        engine = DefaultAutomationEngine(),
        events = notifications,
        beforeNetworkUse = { network.prepare() },
    )

    /** Null until the saved data has been read once (the splash screen waits for it). */
    private val loadedSettings: StateFlow<AppSettings?> =
        repository.data.map<_, AppSettings?> { it.settings }.stateIn(scope, SharingStarted.Eagerly, null)

    val isLoaded: Boolean get() = loadedSettings.value != null
    val settings: StateFlow<AppSettings> =
        repository.data.map { it.settings }.stateIn(scope, SharingStarted.Eagerly, AppSettings())
}

val Context.app: AppContainer get() = (applicationContext as HomeApp).container
