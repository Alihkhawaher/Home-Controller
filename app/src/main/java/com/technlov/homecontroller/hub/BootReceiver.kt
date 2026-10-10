package com.technlov.homecontroller.hub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.technlov.home.core.info
import com.technlov.homecontroller.app
import com.technlov.homecontroller.diag.LogSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Starts the hub after a reboot or an app update, when the user turned on "Run hub in background". */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val wanted = context.app.repository.current().settings.runInBackground
                context.app.eventLog.info(
                    LogSource.SERVICE,
                    "${if (intent.action == Intent.ACTION_BOOT_COMPLETED) "Phone started" else "App updated"}: " +
                        if (wanted) "starting the background hub" else "background hub is off",
                )
                if (wanted) HubService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
