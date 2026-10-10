package com.technlov.homecontroller.ui.device

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DevicesOther
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.MeetingRoom
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.rounded.PowerSettingsNew
import com.technlov.homecontroller.data.PowerButton
import com.technlov.homecontroller.history.PowerHistoryUi
import com.technlov.homecontroller.history.HistoryRange
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.technlov.home.core.Automation
import com.technlov.home.core.Capability
import com.technlov.home.core.DeviceKey
import com.technlov.home.core.DeviceState
import com.technlov.home.core.Keys
import com.technlov.home.core.SetValue
import com.technlov.home.core.Value
import com.technlov.homecontroller.R
import com.technlov.homecontroller.data.SavedDevice
import com.technlov.homecontroller.hub.DeviceIssue
import com.technlov.homecontroller.hub.LinkStatus
import com.technlov.homecontroller.ui.appViewModel
import com.technlov.homecontroller.ui.asString
import com.technlov.homecontroller.ui.capabilityIcon
import com.technlov.homecontroller.ui.categoryIcon
import com.technlov.homecontroller.ui.components.ConfirmDialog
import com.technlov.homecontroller.ui.components.GroupCard
import com.technlov.homecontroller.ui.components.IconBadge
import com.technlov.homecontroller.ui.components.MessageEffect
import com.technlov.homecontroller.ui.components.RoomPickerDialog
import com.technlov.homecontroller.ui.components.SectionHeader
import com.technlov.homecontroller.ui.components.StatusPill
import com.technlov.homecontroller.ui.components.TextInputDialog
import com.technlov.homecontroller.ui.find
import com.technlov.homecontroller.ui.modeColor
import com.technlov.homecontroller.ui.onlineColor
import com.technlov.homecontroller.ui.toUiText
import com.technlov.homecontroller.ui.valueText
import kotlinx.coroutines.delay

private enum class DeviceDialog { RENAME, ROOM, POWER_BUTTON, REMOVE }

@Composable
fun DeviceScreen(
    deviceKey: DeviceKey,
    onBack: () -> Unit,
    onOpenSchedule: (automationId: String?) -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val vm = appViewModel(key = "device:$deviceKey") { DeviceViewModel(it, deviceKey) }
    val ui by vm.ui.collectAsState()
    val retrying by vm.retrying.collectAsState()
    val history by vm.history.collectAsState()
    val schedules by vm.schedules.collectAsState()
    val runInBackground by vm.runInBackground.collectAsState()
    val hasHomeLocation by vm.hasHomeLocation.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(vm.messages, snackbar)
    var dialog by rememberSaveable { mutableStateOf<DeviceDialog?>(null) }
    var pendingPower by remember { mutableStateOf<Pair<List<SetValue>, String>?>(null) }

    val device = ui.device
    if (ui.loaded && device == null) {
        // Removed (here or elsewhere): nothing left to show.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(device?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            ui.roomName ?: stringResource(R.string.room_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (device != null) {
                        DeviceMenu(
                            hidden = device.hidden,
                            hasPower = ui.capabilities.any { it is Capability.Power },
                            onRename = { dialog = DeviceDialog.RENAME },
                            onRoom = { dialog = DeviceDialog.ROOM },
                            onPowerButton = { dialog = DeviceDialog.POWER_BUTTON },
                            onToggleHidden = { vm.setHidden(!device.hidden) },
                            onRemove = { dialog = DeviceDialog.REMOVE },
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (device != null) {
            // "Ask first": a power change waits for a confirmation; everything else applies at once.
            val powerKey = ui.capabilities.firstOrNull { it is Capability.Power }?.key
            val guardedApply: (List<SetValue>, String) -> Unit = { changes, what ->
                if (device.powerButton == PowerButton.CONFIRM && changes.any { it.key == powerKey }) {
                    pendingPower = changes to what
                } else {
                    vm.apply(changes, what)
                }
            }
            DeviceContent(
                ui, device, retrying, padding, onApply = guardedApply, onRetry = vm::retry,
                history = history, onHistoryRange = vm::setHistoryRange,
                schedules = schedules, runInBackground = runInBackground, hasHomeLocation = hasHomeLocation,
                onOpenSchedule = onOpenSchedule, onScheduleEnabled = vm::setScheduleEnabled, onOpenSettings = onOpenSettings,
            )
        }
    }

    if (device != null) {
        when (dialog) {
            DeviceDialog.RENAME -> TextInputDialog(
                title = stringResource(R.string.device_rename_title),
                label = stringResource(R.string.device_name_label),
                initial = device.name,
                confirmLabel = stringResource(R.string.action_save),
                onConfirm = {
                    vm.rename(it)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
            DeviceDialog.ROOM -> RoomPickerDialog(
                rooms = ui.rooms,
                selectedId = device.roomId,
                onPick = {
                    vm.setRoom(it)
                    dialog = null
                },
                onCreate = {
                    vm.createRoomAndMove(it)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
            DeviceDialog.POWER_BUTTON -> PowerButtonDialog(
                selected = device.powerButton,
                onPick = {
                    vm.setPowerButton(it)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
            DeviceDialog.REMOVE -> ConfirmDialog(
                title = stringResource(R.string.device_remove_title, device.name),
                text = stringResource(R.string.device_remove_text),
                confirmLabel = stringResource(R.string.action_remove),
                icon = Icons.Rounded.Delete,
                onConfirm = {
                    dialog = null
                    vm.remove(onBack)
                },
                onDismiss = { dialog = null },
            )
            null -> Unit
        }
        pendingPower?.let { (changes, what) ->
            val turningOn = (changes.firstOrNull { it.key == powerKeyOf(ui) }?.value as? Value.Bool)?.v == true
            ConfirmDialog(
                title = stringResource(if (turningOn) R.string.power_confirm_on else R.string.power_confirm_off, device.name),
                text = stringResource(R.string.power_confirm_text),
                confirmLabel = stringResource(if (turningOn) R.string.action_turn_on else R.string.action_turn_off),
                icon = Icons.Rounded.PowerSettingsNew,
                onConfirm = {
                    pendingPower = null
                    vm.apply(changes, what)
                },
                onDismiss = { pendingPower = null },
            )
        }
    }
}

private fun powerKeyOf(ui: DeviceDetailUi): String? = ui.capabilities.firstOrNull { it is Capability.Power }?.key

/** Show / Ask first / Hide, each with one line saying what it does. */
@Composable
private fun PowerButtonDialog(selected: PowerButton, onPick: (PowerButton) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(
        PowerButton.SHOW to (R.string.power_button_show to R.string.power_button_show_text),
        PowerButton.CONFIRM to (R.string.power_button_confirm to R.string.power_button_confirm_text),
        PowerButton.HIDE to (R.string.power_button_hide to R.string.power_button_hide_text),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null) },
        title = { Text(stringResource(R.string.power_button_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { (mode, labels) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = mode == selected, role = Role.RadioButton, onClick = { onPick(mode) })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(labels.first), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(labels.second),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun DeviceMenu(
    hidden: Boolean,
    hasPower: Boolean,
    onRename: () -> Unit,
    onRoom: () -> Unit,
    onPowerButton: () -> Unit,
    onToggleHidden: () -> Unit,
    onRemove: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.device_menu_rename)) },
            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
            onClick = {
                open = false
                onRename()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.device_menu_room)) },
            leadingIcon = { Icon(Icons.Rounded.MeetingRoom, contentDescription = null) },
            onClick = {
                open = false
                onRoom()
            },
        )
        if (hasPower) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.device_menu_power_button)) },
                leadingIcon = { Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null) },
                onClick = {
                    open = false
                    onPowerButton()
                },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(if (hidden) R.string.device_menu_show else R.string.device_menu_hide)) },
            leadingIcon = {
                Icon(if (hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, contentDescription = null)
            },
            onClick = {
                open = false
                onToggleHidden()
            },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.device_menu_remove)) },
            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
            colors = MenuDefaults.itemColors(
                textColor = MaterialTheme.colorScheme.error,
                leadingIconColor = MaterialTheme.colorScheme.error,
            ),
            onClick = {
                open = false
                onRemove()
            },
        )
    }
}

@Composable
private fun DeviceContent(
    ui: DeviceDetailUi,
    device: SavedDevice,
    retrying: Boolean,
    padding: PaddingValues,
    onApply: (List<SetValue>, String) -> Unit,
    onRetry: () -> Unit,
    history: PowerHistoryUi?,
    onHistoryRange: (HistoryRange) -> Unit,
    schedules: List<Automation>,
    runInBackground: Boolean,
    hasHomeLocation: Boolean,
    onOpenSchedule: (String?) -> Unit,
    onScheduleEnabled: (String, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val caps = ui.capabilities
    val state = ui.state
    val offline = ui.status == LinkStatus.OFFLINE
    val enabled = !offline && state != null

    val target = caps.find(Keys.TARGET_TEMPERATURE) as? Capability.Number
    val power = caps.firstOrNull { it is Capability.Power }
    val mode = caps.find(Keys.MODE) as? Capability.Choice
    val isClimate = target != null

    // Keys drawn by the hero section; everything else is drawn generically below.
    val special = buildSet {
        power?.let { add(it.key) }
        if (isClimate) {
            add(Keys.TARGET_TEMPERATURE)
            add(Keys.CURRENT_TEMPERATURE)
            mode?.let { add(it.key) }
        }
    }
    val rest = caps.filter { it.key !in special }
    val choices = rest.filterIsInstance<Capability.Choice>().filter { it.writable }
    val numbers = rest.filterIsInstance<Capability.Number>().filter { it.writable }
    val toggles = rest.filterIsInstance<Capability.Toggle>()
    val readings = rest.filter {
        (it is Capability.Reading && !it.diagnostic) ||
            (it is Capability.Choice && !it.writable) ||
            (it is Capability.Number && !it.writable)
    }
    val diagnostics = rest.filterIsInstance<Capability.Reading>().filter { it.diagnostic }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 4.dp,
            bottom = padding.calculateBottomPadding() + 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "status") { StatusRow(ui) }
        if (offline) item(key = "offline") { OfflineCard(ui.issue, retrying, onRetry) }
        if (caps.isEmpty() && !offline) item(key = "waiting") { WaitingCard() }

        if (target != null) {
            item(key = "climate") {
                ClimateHero(target, hasPower = power != null, mode = mode, state = state, enabled = enabled, onApply = onApply)
            }
            if (mode != null) {
                item(key = "mode") {
                    val selectedLabel = mode.options.firstOrNull { it.value == state?.text(Keys.MODE) }?.label
                    ControlCard(Icons.Rounded.Tune, mode.label, selectedLabel) {
                        ModeSelector(mode, state?.text(Keys.MODE), enabled) { value ->
                            onApply(listOf(SetValue(mode.key, Value.Text(value))), mode.label)
                        }
                    }
                }
            }
        } else if (power != null) {
            item(key = "power") { PowerHero(device, ui.connectorName, power, state, enabled, onApply) }
        }

        choices.forEach { choice ->
            item(key = "choice:${choice.key}") {
                ChoiceControl(choice, state?.text(choice.key), enabled) { value ->
                    onApply(listOf(SetValue(choice.key, Value.Text(value))), choice.label)
                }
            }
        }
        numbers.forEach { number ->
            item(key = "number:${number.key}") {
                NumberControl(number, state?.number(number.key), enabled) { value ->
                    onApply(listOf(SetValue(number.key, Value.Num(value))), number.label)
                }
            }
        }
        if (toggles.isNotEmpty()) {
            item(key = "toggles") {
                Column {
                    SectionHeader(stringResource(R.string.device_features))
                    GroupCard {
                        toggles.forEach { toggle ->
                            ToggleRow(toggle, state?.bool(toggle.key) == true, enabled) { checked ->
                                onApply(listOf(SetValue(toggle.key, Value.Bool(checked))), toggle.label)
                            }
                        }
                    }
                }
            }
        }
        val settable = caps.any {
            it is Capability.Power || (it is Capability.Choice && it.writable) ||
                (it is Capability.Number && it.writable) || (it is Capability.Toggle && it.writable)
        }
        if (settable) {
            item(key = "schedules") {
                Column {
                    SectionHeader(stringResource(R.string.schedules_title))
                    SchedulesCard(
                        schedules = schedules,
                        capabilities = caps,
                        runInBackground = runInBackground,
                        hasHomeLocation = hasHomeLocation,
                        onOpen = onOpenSchedule,
                        onEnabled = onScheduleEnabled,
                        onOpenSettings = onOpenSettings,
                    )
                }
            }
        }
        if (readings.isNotEmpty()) {
            item(key = "readings") {
                Column {
                    SectionHeader(stringResource(R.string.device_readings))
                    GroupCard {
                        readings.forEach { reading ->
                            ReadingRow(capabilityIcon(reading.key), reading.label, valueText(reading, state?.values?.get(reading.key)))
                        }
                    }
                }
            }
        }
        if (history != null) {
            item(key = "history") {
                Column {
                    SectionHeader(stringResource(R.string.history_title))
                    PowerHistoryCard(history, onHistoryRange)
                }
            }
        }
        item(key = "diagnostics") {
            val rows = mutableListOf<Pair<String, String>>()
            val icons = mutableListOf<ImageVector>()
            diagnostics.forEach {
                rows += it.label to valueText(it, state?.values?.get(it.key))
                icons += capabilityIcon(it.key)
            }
            ui.connectorName?.let {
                rows += stringResource(R.string.device_info_plugin) to it
                icons += Icons.Rounded.Extension
            }
            rows += stringResource(R.string.device_info_address) to device.ref.address
            icons += Icons.Rounded.Lan
            device.ref.model?.let {
                rows += stringResource(R.string.device_info_model) to it
                icons += Icons.Rounded.DevicesOther
            }
            if (diagnostics.none { it.key == Keys.FIRMWARE }) {
                device.ref.firmware?.let {
                    rows += stringResource(R.string.device_info_firmware) to it
                    icons += Icons.Rounded.Memory
                }
            }
            rows += stringResource(R.string.device_info_id) to device.ref.id
            icons += Icons.Rounded.Code
            Column {
                Spacer(Modifier.height(8.dp))
                DiagnosticsCard(rows, icons)
            }
        }
    }
}

@Composable
private fun StatusRow(ui: DeviceDetailUi) {
    val scheme = MaterialTheme.colorScheme
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (ui.status) {
            LinkStatus.ONLINE -> StatusPill(
                stringResource(R.string.status_online),
                container = onlineColor().copy(alpha = 0.16f).compositeOver(scheme.surface),
                content = onlineColor(),
            )
            LinkStatus.CONNECTING -> StatusPill(
                stringResource(R.string.status_connecting),
                container = scheme.surfaceContainerHighest,
                content = scheme.onSurfaceVariant,
            )
            LinkStatus.OFFLINE -> StatusPill(
                stringResource(R.string.status_offline),
                icon = Icons.Rounded.WifiOff,
                container = scheme.errorContainer,
                content = scheme.onErrorContainer,
            )
        }
        ui.lastSeenMillis?.let { seen ->
            val seconds = ((now - seen) / 1_000).coerceAtLeast(0)
            Text(
                when {
                    seconds < 10 -> stringResource(R.string.updated_just_now)
                    seconds < 60 -> pluralStringResource(R.plurals.updated_seconds, seconds.toInt(), seconds.toInt())
                    else -> pluralStringResource(R.plurals.updated_minutes, (seconds / 60).toInt(), (seconds / 60).toInt())
                },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        if (ui.busy) {
            Spacer(Modifier.weight(1f))
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.status_sending), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OfflineCard(issue: DeviceIssue?, retrying: Boolean, onRetry: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WifiOff, contentDescription = null)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.offline_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    issue?.toUiText()?.asString() ?: stringResource(R.string.offline_text),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onRetry, enabled = !retrying) {
                if (retrying) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

@Composable
private fun WaitingCard() {
    GroupCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Spacer(Modifier.width(16.dp))
            Text(stringResource(R.string.device_waiting), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ClimateHero(
    target: Capability.Number,
    hasPower: Boolean,
    mode: Capability.Choice?,
    state: DeviceState?,
    enabled: Boolean,
    onApply: (List<SetValue>, String) -> Unit,
) {
    val view = LocalView.current
    val powerOn = state?.bool(Keys.POWER) ?: !hasPower
    val modeValue = state?.text(Keys.MODE)
    val color = modeColor(modeValue)
    val actual = state?.number(Keys.TARGET_TEMPERATURE)
    val step = target.step.takeIf { it > 0 } ?: 1.0
    val canSet = enabled && target.writable

    // The value being chosen: shown at once, sent after a short pause (one command, not one per degree).
    var draft by remember { mutableStateOf<Double?>(null) }
    var dragging by remember { mutableStateOf(false) }
    val shown = draft ?: actual ?: target.min
    LaunchedEffect(draft, dragging) {
        val pending = draft ?: return@LaunchedEffect
        if (dragging) return@LaunchedEffect
        delay(400)
        onApply(listOf(SetValue(target.key, Value.Num(pending))), target.label)
        delay(2_500)
        draft = null // the device never confirmed: fall back to what it reports
    }
    LaunchedEffect(actual) {
        if (!dragging && draft != null && actual == draft) draft = null
    }

    val modeLabel = mode?.options?.firstOrNull { it.value == modeValue }?.label
    val topLabel = when {
        state == null -> target.label
        !powerOn -> stringResource(R.string.value_off)
        modeLabel != null -> modeLabel
        else -> target.label
    }
    val powerLabel = stringResource(R.string.action_power)

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        TemperatureDial(
            value = shown,
            min = target.min,
            max = target.max,
            step = step,
            color = color,
            active = powerOn && state != null,
            enabled = canSet,
            topLabel = topLabel,
            current = state?.number(Keys.CURRENT_TEMPERATURE),
            unit = target.unit.ifEmpty { "°" },
            onValueChange = { draft = it },
            onDragChange = { dragging = it },
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 380.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            StepButton(Icons.Rounded.Remove, stringResource(R.string.action_decrease), canSet && shown > target.min) {
                draft = snapToStep(shown - step, target.min, target.max, step)
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            if (hasPower) {
                BigPowerButton(on = powerOn && state != null, enabled = enabled, accent = color, onClick = {
                    onApply(listOf(SetValue(Keys.POWER, Value.Bool(!powerOn))), powerLabel)
                })
            }
            StepButton(Icons.Rounded.Add, stringResource(R.string.action_increase), canSet && shown < target.max) {
                draft = snapToStep(shown + step, target.min, target.max, step)
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(56.dp)) {
        Icon(icon, contentDescription = description)
    }
}

@Composable
private fun PowerHero(
    device: SavedDevice,
    connectorName: String?,
    power: Capability,
    state: DeviceState?,
    enabled: Boolean,
    onApply: (List<SetValue>, String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val on = state?.bool(power.key) == true
    val accent = scheme.primary
    val container by animateColorAsState(
        if (on) accent.copy(alpha = 0.14f).compositeOver(scheme.surfaceContainerLow) else scheme.surfaceContainerLow,
        label = "heroContainer",
    )
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = categoryIcon(device.ref.category),
                tint = if (on) accent else scheme.onSurfaceVariant,
                container = if (on) accent.copy(alpha = 0.2f) else scheme.surfaceContainerHighest,
                size = 72.dp,
                iconSize = 36.dp,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(
                        when {
                            state == null -> R.string.status_connecting
                            on -> R.string.value_on
                            else -> R.string.value_off
                        },
                    ),
                    style = MaterialTheme.typography.headlineMedium,
                )
                (device.ref.model ?: connectorName)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1)
                }
            }
            BigPowerButton(on = on, enabled = enabled, accent = accent, size = 64.dp, onClick = {
                onApply(listOf(SetValue(power.key, Value.Bool(!on))), power.label)
            })
        }
    }
}
