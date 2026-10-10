package com.technlov.homecontroller.ui.home

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.technlov.homecontroller.ui.components.ConfirmDialog
import com.technlov.homecontroller.data.PowerButton
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Cottage
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.technlov.home.core.DeviceKey
import com.technlov.homecontroller.R
import com.technlov.homecontroller.hub.LinkStatus
import com.technlov.homecontroller.ui.appViewModel
import com.technlov.homecontroller.ui.categoryIcon
import com.technlov.homecontroller.ui.components.IconBadge
import com.technlov.homecontroller.ui.components.MessageEffect
import com.technlov.homecontroller.ui.components.PillShape
import com.technlov.homecontroller.ui.components.StatusPill
import com.technlov.homecontroller.ui.components.isScrollingUp
import com.technlov.homecontroller.ui.formatNumber
import com.technlov.homecontroller.ui.modeColor
import com.technlov.homecontroller.ui.modeIcon
import com.technlov.homecontroller.ui.theme.NumberStyle
import java.time.LocalTime

@Composable
fun HomeScreen(onOpenDevice: (DeviceKey) -> Unit, onFindDevices: () -> Unit, onPairDevice: (() -> Unit)?) {
    var confirmPower by remember { mutableStateOf<DeviceTileUi?>(null) }
    val vm = appViewModel { HomeViewModel(it) }
    val ui by vm.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(vm.messages, snackbar)

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pullState = rememberPullToRefreshState()
    if (pullState.isRefreshing) {
        LaunchedEffect(true) {
            try {
                vm.refresh()
            } finally {
                pullState.endRefresh()
            }
        }
    }
    val gridState = rememberLazyGridState()

    Scaffold(
        // The pull-to-refresh connection sits outside the app bar's, so a pull first expands the bar, then refreshes.
        modifier = Modifier
            .nestedScroll(pullState.nestedScrollConnection)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(greeting(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = onFindDevices) {
                        Icon(Icons.Rounded.Radar, contentDescription = stringResource(R.string.action_find_devices))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (ui.deviceCount > 0) {
                ExtendedFloatingActionButton(
                    onClick = onFindDevices,
                    expanded = gridState.isScrollingUp(),
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_add_device)) },
                )
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                !ui.loaded -> Unit
                ui.deviceCount == 0 -> EmptyHome(onFindDevices, onPairDevice, Modifier.fillMaxSize())
                else -> DeviceGrid(
                    ui = ui,
                    gridState = gridState,
                    onOpenDevice = onOpenDevice,
                    onTogglePower = { tile ->
                        if (tile.powerButton == PowerButton.CONFIRM) confirmPower = tile
                        else vm.setPower(tile.key, tile.name, !tile.isActive)
                    },
                    onShowHidden = vm::setShowHidden,
                )
            }
            if (pullState.isRefreshing || pullState.verticalOffset > 0f) {
                PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
            }
        }
    }

    confirmPower?.let { tile ->
        val turningOn = !tile.isActive
        ConfirmDialog(
            title = stringResource(if (turningOn) R.string.power_confirm_on else R.string.power_confirm_off, tile.name),
            text = stringResource(R.string.power_confirm_text),
            confirmLabel = stringResource(if (turningOn) R.string.action_turn_on else R.string.action_turn_off),
            icon = Icons.Rounded.PowerSettingsNew,
            onConfirm = {
                confirmPower = null
                vm.setPower(tile.key, tile.name, turningOn)
            },
            onDismiss = { confirmPower = null },
        )
    }
}

@Composable
private fun greeting(): String {
    val hour = LocalTime.now().hour
    return stringResource(
        when (hour) {
            in 5..11 -> R.string.greeting_morning
            in 12..16 -> R.string.greeting_afternoon
            in 17..21 -> R.string.greeting_evening
            else -> R.string.greeting_night
        },
    )
}

@Composable
private fun DeviceGrid(
    ui: HomeUi,
    gridState: LazyGridState,
    onOpenDevice: (DeviceKey) -> Unit,
    onTogglePower: (DeviceTileUi) -> Unit,
    onShowHidden: (Boolean) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 164.dp),
        state = gridState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 112.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "summary", span = { GridItemSpan(maxLineSpan) }) { SummaryCard(ui) }
        ui.sections.forEach { section ->
            item(key = "room:${section.roomId ?: "none"}", span = { GridItemSpan(maxLineSpan) }) {
                RoomHeader(
                    name = section.roomName ?: stringResource(R.string.room_none),
                    count = section.devices.size,
                    modifier = Modifier.animateItemPlacement(),
                )
            }
            items(section.devices, key = { "device:${it.key}" }) { tile ->
                DeviceTile(
                    tile = tile,
                    onClick = { onOpenDevice(tile.key) },
                    onTogglePower = { onTogglePower(tile) },
                    modifier = Modifier.animateItemPlacement(),
                )
            }
        }
        if (ui.hiddenCount > 0) {
            item(key = "hidden", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { onShowHidden(!ui.showHidden) }) {
                        Icon(
                            if (ui.showHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (ui.showHidden) stringResource(R.string.home_hide_hidden)
                            else pluralStringResource(R.plurals.home_show_hidden, ui.hiddenCount, ui.hiddenCount),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(ui: HomeUi) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            SummaryStat(Icons.Rounded.PowerSettingsNew, ui.onCount.toString(), stringResource(R.string.summary_on))
            ui.averageTemperature?.let {
                SummaryStat(Icons.Rounded.Thermostat, formatNumber(it) + "°", stringResource(R.string.summary_inside))
            }
            SummaryStat(
                Icons.Rounded.Wifi,
                stringResource(R.string.summary_fraction, ui.onlineCount, ui.deviceCount),
                stringResource(R.string.summary_online),
            )
        }
    }
}

@Composable
private fun SummaryStat(icon: ImageVector, value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp).alpha(0.75f))
        Spacer(Modifier.height(6.dp))
        AnimatedContent(targetState = value, label = "summaryValue") { Text(it, style = MaterialTheme.typography.headlineSmall) }
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.alpha(0.8f))
    }
}

@Composable
private fun RoomHeader(name: String, count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            pluralStringResource(R.plurals.device_count, count, count),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A readable colour on top of a bright accent. */
fun contentOn(accent: Color): Color = if (accent.luminance() > 0.45f) Color(0xFF0F1A1F) else Color.White

@Composable
fun DeviceTile(tile: DeviceTileUi, onClick: () -> Unit, onTogglePower: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val accent = if (tile.isClimate) modeColor(tile.mode) else scheme.primary
    val active = tile.isActive
    val offline = tile.status == LinkStatus.OFFLINE
    val container by animateColorAsState(
        if (active) accent.copy(alpha = 0.16f).compositeOver(scheme.surfaceContainerLow) else scheme.surfaceContainerLow,
        label = "tileContainer",
    )

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (tile.hidden) 0.6f else 1f),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .animateContentSize(),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                val icon = if (tile.isClimate && active) modeIcon(tile.mode) else categoryIcon(tile.category)
                Crossfade(targetState = icon to active, label = "tileIcon") { (shown, on) ->
                    IconBadge(
                        icon = shown,
                        tint = if (on) accent else scheme.onSurfaceVariant,
                        container = if (on) accent.copy(alpha = 0.18f) else scheme.surfaceContainerHighest,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (tile.hasPower && tile.powerButton != PowerButton.HIDE) {
                    TilePowerButton(on = active, enabled = !offline, accent = accent, onClick = onTogglePower)
                }
            }
            Spacer(Modifier.height(14.dp))
            TileReadout(tile, offline)
            Text(
                tile.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            TileStatus(tile, accent)
        }
    }
}

@Composable
private fun TileReadout(tile: DeviceTileUi, offline: Boolean) {
    val color = if (offline) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val current = tile.currentTemperature
    when {
        current != null -> Row(verticalAlignment = Alignment.Top) {
            AnimatedContent(
                targetState = formatNumber(current),
                transitionSpec = {
                    (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
                },
                label = "tileTemperature",
            ) { text -> Text(text, style = NumberStyle, fontSize = 44.sp, color = color, maxLines = 1) }
            Text("°", style = NumberStyle, fontSize = 30.sp, color = color, modifier = Modifier.padding(top = 2.dp))
        }
        tile.readout != null -> Text(tile.readout, style = NumberStyle, fontSize = 40.sp, color = color, maxLines = 1)
        else -> Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TileStatus(tile: DeviceTileUi, accent: Color) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            tile.status == LinkStatus.OFFLINE -> StatusPill(
                text = stringResource(R.string.status_offline),
                icon = Icons.Rounded.WifiOff,
                container = scheme.errorContainer,
                content = scheme.onErrorContainer,
            )
            tile.status == LinkStatus.CONNECTING && tile.isOn == null -> StatusPill(
                text = stringResource(R.string.status_connecting),
                container = scheme.surfaceContainerHighest,
                content = scheme.onSurfaceVariant,
            )
            tile.isOn == true -> StatusPill(
                text = stringResource(R.string.value_on),
                container = accent,
                content = contentOn(accent),
            )
            tile.isOn == false -> StatusPill(
                text = stringResource(R.string.value_off),
                container = scheme.surfaceContainerHighest,
                content = scheme.onSurfaceVariant,
            )
        }
        val detail = when {
            tile.status == LinkStatus.OFFLINE -> null
            tile.isClimate && tile.targetTemperature != null && tile.isOn == true ->
                stringResource(R.string.tile_mode_to_target, tile.modeLabel ?: "", formatNumber(tile.targetTemperature) + "°")
            tile.isClimate && tile.targetTemperature != null ->
                stringResource(R.string.tile_set_to, formatNumber(tile.targetTemperature) + "°")
            else -> tile.readoutLabel
        }
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun TilePowerButton(on: Boolean, enabled: Boolean, accent: Color, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(if (on) accent else scheme.surfaceContainerHighest, label = "powerContainer")
    val content by animateColorAsState(if (on) contentOn(accent) else scheme.onSurfaceVariant, label = "powerContent")
    val state = stringResource(if (on) R.string.value_on else R.string.value_off)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = container,
        contentColor = content,
        modifier = Modifier
            .size(44.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .semantics {
                role = Role.Switch
                stateDescription = state
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.PowerSettingsNew,
                contentDescription = stringResource(R.string.action_power),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

// ---- empty state ----

@Composable
private fun EmptyHome(onFindDevices: () -> Unit, onPairDevice: (() -> Unit)?, modifier: Modifier = Modifier) {
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
            HomeIllustration(Modifier.size(260.dp))
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.empty_home_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.empty_home_text),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onFindDevices,
                shape = PillShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
            ) {
                Icon(Icons.Rounded.Radar, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.action_find_devices), style = MaterialTheme.typography.titleMedium)
            }
            if (onPairDevice != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onPairDevice,
                    shape = PillShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Icon(Icons.Rounded.AddCircle, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.pairing_entry_title), style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.empty_home_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** A house in the middle with the kinds of things it controls floating around it. */
@Composable
private fun HomeIllustration(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "float")
    val lift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "lift",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            drawCircle(scheme.primary.copy(alpha = 0.06f), radius = r)
            drawCircle(scheme.primary.copy(alpha = 0.09f), radius = r * 0.66f)
            drawCircle(
                scheme.primary.copy(alpha = 0.28f),
                radius = r * 0.82f,
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 12f))),
            )
        }
        Surface(
            shape = CircleShape,
            color = scheme.primaryContainer,
            shadowElevation = 6.dp,
            modifier = Modifier.size(108.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Cottage, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(54.dp))
            }
        }
        OrbitIcon(Icons.Rounded.AcUnit, modeColor("cool"), (-88).dp, (-56).dp, lift)
        OrbitIcon(Icons.Rounded.Lightbulb, scheme.tertiary, 92.dp, (-40).dp, 1f - lift)
        OrbitIcon(Icons.Rounded.Tv, scheme.secondary, (-80).dp, 70.dp, 1f - lift)
        OrbitIcon(Icons.Rounded.Wifi, scheme.primary, 84.dp, 74.dp, lift)
    }
}

@Composable
private fun OrbitIcon(icon: ImageVector, tint: Color, x: Dp, y: Dp, lift: Float) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 3.dp,
        modifier = Modifier
            .offset(x, y + (lift * 8f).dp)
            .size(52.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
        }
    }
}
