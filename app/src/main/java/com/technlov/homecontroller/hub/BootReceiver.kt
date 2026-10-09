package com.technlov.homecontroller.hub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.technlov.homecontroller.app
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
                if (context.app.repository.current().settings.runInBackground) HubService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
