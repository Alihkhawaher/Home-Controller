package com.technlov.homecontroller.ui.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.MeetingRoom
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.technlov.homecontroller.BuildConfig
import com.technlov.homecontroller.R
import com.technlov.homecontroller.data.AppSettings
import com.technlov.homecontroller.data.ThemeMode
import com.technlov.homecontroller.ui.OnResume
import com.technlov.homecontroller.ui.appViewModel
import com.technlov.homecontroller.ui.components.ConfirmDialog
import com.technlov.homecontroller.ui.components.GroupCard
import com.technlov.homecontroller.ui.components.SectionHeader
import com.technlov.homecontroller.ui.ignoringBatteryOptimizations
import com.technlov.homecontroller.ui.onlineColor
import com.technlov.homecontroller.ui.openBatteryOptimizationSettings
import com.technlov.homecontroller.ui.rememberNotificationAccess
import com.technlov.homecontroller.ui.theme.supportsDynamicColor

@Composable
fun SettingsScreen(
    onOpenRooms: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val vm = appViewModel { SettingsViewModel(it) }
    val ui by vm.ui.collectAsState()
    val settings = ui.settings
    val context = LocalContext.current

    var batteryOk by remember { mutableStateOf(ignoringBatteryOptimizations(context)) }
    OnResume { batteryOk = ignoringBatteryOptimizations(context) }
    var offerBattery by remember { mutableStateOf(false) }
    val notifications = rememberNotificationAccess { if (!batteryOk) offerBattery = true }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text(stringResource(R.string.nav_settings)) }, scrollBehavior = scrollBehavior) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "hubHeader") { SectionHeader(stringResource(R.string.settings_hub)) }
            item(key = "hub") {
                GroupCard {
                    SwitchRow(
                        icon = Icons.Rounded.Hub,
                        title = stringResource(R.string.settings_background),
                        text = stringResource(R.string.settings_background_text),
                        checked = settings.runInBackground,
                    ) { on ->
                        vm.update { it.copy(runInBackground = on) }
                        if (on) {
                            val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifications.asked
                            when {
                                !notifications.granted && canAsk -> notifications.request()
                                !batteryOk -> offerBattery = true
                            }
                        }
                    }
                    RowDivider()
                    SegmentsRow(
                        icon = Icons.Rounded.Sync,
                        title = stringResource(R.string.settings_poll_foreground),
                        options = AppSettings.FOREGROUND_POLL_OPTIONS,
                        selected = settings.foregroundPollSeconds,
                        label = { intervalLabel(it) },
                        onSelect = { s -> vm.update { it.copy(foregroundPollSeconds = s) } },
                    )
                    SegmentsRow(
                        icon = Icons.Rounded.Schedule,
                        title = stringResource(R.string.settings_poll_background),
                        options = AppSettings.BACKGROUND_POLL_OPTIONS,
                        selected = settings.backgroundPollSeconds,
                        label = { intervalLabel(it) },
                        enabled = settings.runInBackground,
                        onSelect = { s -> vm.update { it.copy(backgroundPollSeconds = s) } },
                    )
                }
            }

            item(key = "permissionsHeader") { SectionHeader(stringResource(R.string.settings_permissions)) }
            item(key = "permissions") {
                GroupCard {
                    StatusRow(
                        icon = Icons.Rounded.NotificationsActive,
                        title = stringResource(R.string.settings_notifications),
                        text = stringResource(
                            if (notifications.granted) R.string.settings_notifications_on else R.string.settings_notifications_off,
                        ),
                        ok = notifications.granted,
                        actionLabel = stringResource(if (notifications.asked) R.string.action_open_settings else R.string.action_allow),
                        onAction = notifications.request,
                    )
                    RowDivider()
                    StatusRow(
                        icon = Icons.Rounded.BatteryChargingFull,
                        title = stringResource(R.string.settings_battery),
                        text = stringResource(if (batteryOk) R.string.settings_battery_on else R.string.settings_battery_off),
                        ok = batteryOk,
                        actionLabel = stringResource(R.string.action_allow),
                        onAction = { openBatteryOptimizationSettings(context) },
                    )
                }
            }

            item(key = "appearanceHeader") { SectionHeader(stringResource(R.string.settings_appearance)) }
            item(key = "appearance") {
                GroupCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(10.dp))
                        val modes = listOf(
                            Triple(ThemeMode.SYSTEM, Icons.Rounded.BrightnessAuto, R.string.theme_system),
                            Triple(ThemeMode.LIGHT, Icons.Rounded.LightMode, R.string.theme_light),
                            Triple(ThemeMode.DARK, Icons.Rounded.DarkMode, R.string.theme_dark),
                        )
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            modes.forEachIndexed { index, (mode, icon, label) ->
                                SegmentedButton(
                                    selected = settings.theme == mode,
                                    onClick = { vm.update { it.copy(theme = mode) } },
                                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                                    icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                ) { Text(stringResource(label), maxLines = 1) }
                            }
                        }
                    }
                    if (supportsDynamicColor()) {
                        RowDivider()
                        SwitchRow(
                            icon = Icons.Rounded.Palette,
                            title = stringResource(R.string.settings_dynamic_color),
                            text = stringResource(R.string.settings_dynamic_color_text),
                            checked = settings.dynamicColor,
                        ) { on -> vm.update { it.copy(dynamicColor = on) } }
                    }
                }
            }

            item(key = "homeHeader") { SectionHeader(stringResource(R.string.settings_home)) }
            item(key = "home") {
                GroupCard {
                    NavRow(
                        icon = Icons.Rounded.MeetingRoom,
                        title = stringResource(R.string.settings_rooms),
                        text = pluralStringResource(R.plurals.rooms_count, ui.rooms.size, ui.rooms.size),
                        onClick = onOpenRooms,
                    )
                }
            }

            item(key = "aboutHeader") { SectionHeader(stringResource(R.string.settings_about_section)) }
            item(key = "about") {
                GroupCard {
                    NavRow(
                        icon = Icons.Rounded.Extension,
                        title = stringResource(R.string.settings_plugins),
                        text = pluralStringResource(R.plurals.plugins_installed, vm.connectors.size, vm.connectors.size),
                        onClick = onOpenPlugins,
                    )
                    RowDivider()
                    NavRow(
                        icon = Icons.Rounded.MonitorHeart,
                        title = stringResource(R.string.settings_diagnostics),
                        text = stringResource(R.string.settings_diagnostics_text),
                        onClick = onOpenDiagnostics,
                    )
                    RowDivider()
                    NavRow(
                        icon = Icons.Rounded.Info,
                        title = stringResource(R.string.settings_about),
                        text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                        onClick = onOpenAbout,
                    )
                }
            }
        }
    }

    if (offerBattery) {
        ConfirmDialog(
            title = stringResource(R.string.battery_dialog_title),
            text = stringResource(R.string.battery_dialog_text),
            confirmLabel = stringResource(R.string.action_open_settings),
            icon = Icons.Rounded.BatteryChargingFull,
            onConfirm = {
                offerBattery = false
                openBatteryOptimizationSettings(context)
            },
            onDismiss = { offerBattery = false },
        )
    }
}

@Composable
private fun intervalLabel(seconds: Int): String =
    if (seconds < 60) stringResource(R.string.interval_seconds, seconds)
    else stringResource(R.string.interval_minutes, seconds / 60)

@Composable
private fun RowDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, text: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = text?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun NavRow(icon: ImageVector, title: String, text: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = text?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun StatusRow(
    icon: ImageVector,
    title: String,
    text: String,
    ok: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(text) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            if (ok) Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = onlineColor())
            else TextButton(onClick = onAction) { Text(actionLabel) }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun <T> SegmentsRow(
    icon: ImageVector,
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    Column(
        Modifier
            .padding(bottom = 12.dp)
            .alpha(if (enabled) 1f else 0.5f),
    ) {
        ListItem(
            headlineContent = { Text(title) },
            leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(label(option), maxLines = 1) }
            }
        }
    }
}
