package com.technlov.homecontroller.ui.automations

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.technlov.home.core.Automation
import com.technlov.home.core.Trigger
import com.technlov.homecontroller.R
import com.technlov.homecontroller.ui.appViewModel
import com.technlov.homecontroller.ui.components.IconBadge
import com.technlov.homecontroller.ui.components.PillShape
import com.technlov.homecontroller.ui.components.isScrollingUp
import java.time.LocalDateTime

@Composable
fun AutomationsScreen(onEdit: (String?) -> Unit) {
    val vm = appViewModel { AutomationsViewModel(it) }
    val ui by vm.ui.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(title = { Text(stringResource(R.string.nav_automations)) }, scrollBehavior = scrollBehavior)
        },
        floatingActionButton = {
            if (ui.automations.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { onEdit(null) },
                    expanded = listState.isScrollingUp(),
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.automation_new)) },
                )
            }
        },
    ) { padding ->
        when {
            !ui.loaded -> Box(Modifier.padding(padding))
            ui.automations.isEmpty() -> EmptyAutomations(onCreate = { onEdit(null) }, modifier = Modifier.padding(padding).fillMaxSize())
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 104.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(ui.automations, key = { it.id }) { automation ->
                    AutomationCard(
                        automation = automation,
                        devices = ui.devices,
                        lastRun = ui.lastRuns[automation.id],
                        needsLocation = automation.trigger is Trigger.Sun && !ui.hasHomeLocation,
                        onClick = { onEdit(automation.id) },
                        onEnabled = { vm.setEnabled(automation.id, it) },
                        modifier = Modifier.animateItemPlacement(),
                    )
                }
            }
        }
    }
}

@Composable
private fun AutomationCard(
    automation: Automation,
    devices: List<DeviceOption>,
    lastRun: LocalDateTime?,
    needsLocation: Boolean,
    onClick: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (automation.enabled) scheme.surfaceContainerLow else scheme.surfaceContainerLowest,
        label = "automationContainer",
    )
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            IconBadge(
                icon = when (automation.trigger) {
                    is Trigger.TimeOfDay -> Icons.Rounded.Schedule
                    is Trigger.Sun -> Icons.Rounded.WbTwilight
                    is Trigger.StateMatches -> Icons.Rounded.Sensors
                },
                tint = if (automation.enabled) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                container = if (automation.enabled) scheme.primaryContainer else scheme.surfaceContainerHighest,
            )
            Spacer(Modifier.width(14.dp))
            Column(
                Modifier
                    .weight(1f)
                    .alpha(if (automation.enabled) 1f else 0.6f),
            ) {
                Text(automation.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(triggerSummary(automation.trigger, devices, automation.runOnce), style = MaterialTheme.typography.bodyMedium)
                automation.actions.forEach { action ->
                    Text(
                        actionSummary(action, devices),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (needsLocation) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.sun_needs_location_short), style = MaterialTheme.typography.labelSmall, color = scheme.error)
                }
                if (lastRun != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(lastRunText(lastRun), style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                }
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = automation.enabled, onCheckedChange = onEnabled)
        }
    }
}

@Composable
private fun EmptyAutomations(onCreate: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                Surface(shape = CircleShape, color = scheme.primary.copy(alpha = 0.07f), modifier = Modifier.size(200.dp)) {}
                Surface(shape = CircleShape, color = scheme.tertiaryContainer, shadowElevation = 6.dp, modifier = Modifier.size(96.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = scheme.onTertiaryContainer, modifier = Modifier.size(48.dp))
                    }
                }
                FloatingBadge(Icons.Rounded.Schedule, scheme.primary, (-70).dp, (-50).dp)
                FloatingBadge(Icons.Rounded.Bolt, scheme.tertiary, 72.dp, (-30).dp)
                FloatingBadge(Icons.Rounded.NotificationsActive, scheme.secondary, 10.dp, 76.dp)
            }
            Spacer(Modifier.height(28.dp))
            Text(
                stringResource(R.string.automations_empty_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.automations_empty_text),
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onCreate, shape = PillShape, modifier = Modifier.height(56.dp)) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.automation_create_first), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun FloatingBadge(icon: ImageVector, tint: Color, x: Dp, y: Dp) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 3.dp,
        modifier = Modifier
            .offset(x, y)
            .size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = tint) }
    }
}
