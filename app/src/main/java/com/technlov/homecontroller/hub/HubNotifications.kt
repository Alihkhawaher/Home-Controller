package com.technlov.homecontroller.hub

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.technlov.home.core.Automation
import com.technlov.homecontroller.MainActivity
import com.technlov.homecontroller.R
import com.technlov.homecontroller.ui.toUiText
import java.util.concurrent.atomic.AtomicInteger

/** The hub's notifications: the quiet "Hub running" one, automation messages, and automation problems. */
class HubNotifications(context: Context) : HubEvents {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)
    private val nextId = AtomicInteger(FIRST_MESSAGE_ID)

    fun createChannels() {
        val channels = listOf(
            NotificationChannelCompat.Builder(CHANNEL_HUB, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.channel_hub))
                .setDescription(context.getString(R.string.channel_hub_description))
                .setShowBadge(false)
                .build(),
            NotificationChannelCompat.Builder(CHANNEL_AUTOMATIONS, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.channel_automations))
                .setDescription(context.getString(R.string.channel_automations_description))
                .build(),
            NotificationChannelCompat.Builder(CHANNEL_PROBLEMS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.channel_problems))
                .setDescription(context.getString(R.string.channel_problems_description))
                .build(),
        )
        manager.createNotificationChannelsCompat(channels)
    }

    fun hubNotification(devices: Int, online: Int): Notification {
        val title = if (devices == 0) context.getString(R.string.hub_notification_title_empty)
        else context.resources.getQuantityString(R.plurals.hub_notification_title, devices, devices)
        val text = when {
            devices == 0 -> context.getString(R.string.hub_notification_text_empty)
            online == devices -> context.getString(R.string.hub_notification_text_all_online)
            online == 0 -> context.getString(R.string.hub_notification_text_none_online)
            else -> context.getString(R.string.hub_notification_text_some, online, devices - online)
        }
        return NotificationCompat.Builder(context, CHANNEL_HUB)
            .setSmallIcon(R.drawable.ic_stat_hub)
            .setColor(BRAND_COLOR)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .build()
    }

    fun updateHub(devices: Int, online: Int) {
        if (!canPost()) return
        try {
            manager.notify(HUB_NOTIFICATION_ID, hubNotification(devices, online))
        } catch (e: SecurityException) {
            // Permission withdrawn between the check and the call; the hub keeps running without the update.
        }
    }

    override fun notify(title: String, text: String) = post(CHANNEL_AUTOMATIONS, title, text)

    override fun automationFailed(automation: Automation, deviceName: String, issue: DeviceIssue) = post(
        CHANNEL_PROBLEMS,
        context.getString(R.string.automation_failed_title, automation.name),
        context.getString(R.string.automation_failed_text, deviceName, issue.toUiText().resolve(context)),
    )

    private fun post(channel: String, title: String, text: String) {
        if (!canPost()) return
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_hub)
            .setColor(BRAND_COLOR)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        try {
            manager.notify(nextId.getAndIncrement(), notification)
        } catch (e: SecurityException) {
            // Permission withdrawn between the check and the call.
        }
    }

    fun canPost(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return granted && manager.areNotificationsEnabled()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_HUB = "hub"
        const val CHANNEL_AUTOMATIONS = "automations"
        const val CHANNEL_PROBLEMS = "problems"
        const val HUB_NOTIFICATION_ID = 1
        private const val FIRST_MESSAGE_ID = 100
        private const val BRAND_COLOR = 0xFF0B6780.toInt()
    }
}
