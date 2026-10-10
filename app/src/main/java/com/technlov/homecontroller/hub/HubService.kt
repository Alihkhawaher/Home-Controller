package com.technlov.homecontroller.hub

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.technlov.home.core.error
import com.technlov.home.core.info
import com.technlov.home.core.warn
import com.technlov.homecontroller.app
import com.technlov.homecontroller.diag.LogSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The hub as a foreground service: while it runs, [Hub.run] polls the devices and runs automations, and a quiet
 * notification says so ("Hub running · 4 devices"). It runs while the app is open, and keeps running after the app
 * closes (and after a reboot) when the user turned on "Run hub in background".
 */
class HubService : Service() {
    // Last line of defence: device errors are handled inside the hub, but a bug must never crash the service.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Hub error", e)
            runCatching { app.eventLog.error(LogSource.SERVICE, "Hub error", e) }
        },
    )

    override fun onCreate() {
        super.onCreate()
        val container = app
        goForeground(container.notifications.hubNotification(0, 0))
        container.eventLog.info(LogSource.SERVICE, "Hub service started (foreground)")
        scope.launch {
            while (isActive) {
                try {
                    container.hub.run()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Hub stopped unexpectedly; restarting", e)
                    container.eventLog.error(LogSource.SERVICE, "Hub stopped unexpectedly; restarting in 5 s", e)
                    delay(RESTART_DELAY_MS)
                }
            }
        }
        scope.launch {
            container.hub.live
                .map { live -> live.size to live.values.count { it.status == LinkStatus.ONLINE } }
                .distinctUntilChanged()
                .collect { (devices, online) -> container.notifications.updateHub(devices, online) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Restarted by the system after being killed: only carry on if the user wants a background hub.
            scope.launch {
                val wanted = app.repository.current().settings.runInBackground || app.hub.appVisible.value
                app.eventLog.info(LogSource.SERVICE, "Restarted by Android; ${if (wanted) "carrying on" else "not wanted, stopping"}")
                if (!wanted) stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        app.eventLog.info(LogSource.SERVICE, "Hub service stopped")
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                HubNotifications.HUB_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(HubNotifications.HUB_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "HubService"
        private const val RESTART_DELAY_MS = 5_000L

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, HubService::class.java))
            } catch (e: RuntimeException) {
                // Android refuses foreground services started from the background in some states; the app keeps
                // working while open (the UI drives the hub directly) and the next app start tries again.
                Log.w(TAG, "Could not start the hub service", e)
                context.app.eventLog.warn(LogSource.SERVICE, "Android did not let the hub service start", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HubService::class.java))
        }
    }
}
