package com.technlov.homecontroller.ui.device

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.technlov.home.core.Action
import com.technlov.home.core.Automation
import com.technlov.home.core.Capability
import com.technlov.home.core.Trigger
import com.technlov.homecontroller.R
import com.technlov.homecontroller.ui.automations.formatTime
import com.technlov.homecontroller.ui.automations.repeatText
import com.technlov.homecontroller.ui.automations.sunShortText
import com.technlov.homecontroller.ui.valueText

/**
 * The device's schedules: "at this time (or at sunrise / sunset), on these days, set this". Each is an ordinary
 * automation, so it runs in the hub on the phone (no cloud, no Wi-Fi needed on the device's side) and also shows in
 * Automations.
 */
@Composable
fun SchedulesCard(
    schedules: List<Automation>,
    capabilities: List<Capability>,
    runInBackground: Boolean,
    hasHomeLocation: Boolean,
    onOpen: (String?) -> Unit,
    onEnabled: (String, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            if (!runInBackground) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = scheme.tertiary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.schedules_background_off),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.schedules_open_settings)) }
                    }
                }
                HorizontalDivider()
            }
            if (schedules.isEmpty()) {
                Text(
                    stringResource(R.string.schedules_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            schedules.forEach { automation ->
                val (title, days) = when (val t = automation.trigger) {
                    is Trigger.TimeOfDay -> formatTime(t.hour, t.minute) to t.days
                    is Trigger.Sun -> sunShortText(t.event, t.offsetMinutes) to t.days
                    is Trigger.StateMatches -> return@forEach
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(automation.id) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (automation.enabled) scheme.onSurface else scheme.onSurfaceVariant,
                        )
                        Text(
                            repeatText(days, automation.runOnce) + " · " + changesText(automation, capabilities),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                        if (automation.trigger is Trigger.Sun && !hasHomeLocation) {
                            Text(
                                stringResource(R.string.sun_needs_location_short),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.error,
                            )
                        }
                    }
                    Switch(checked = automation.enabled, onCheckedChange = { onEnabled(automation.id, it) })
                }
            }
            TextButton(onClick = { onOpen(null) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.schedules_add))
            }
        }
    }
}

/** "Power: On, Mode: Cool" -- what the schedule sets, in the device's own words. */
@Composable
private fun changesText(automation: Automation, capabilities: List<Capability>): String =
    automation.actions.filterIsInstance<Action.SetValues>().flatMap { it.changes }.map { change ->
        val capability = capabilities.firstOrNull { it.key == change.key }
        (capability?.label ?: change.key) + ": " + valueText(capability, change.value)
    }.joinToString(", ")
