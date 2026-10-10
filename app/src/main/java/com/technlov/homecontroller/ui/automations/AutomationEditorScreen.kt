package com.technlov.homecontroller.ui.automations

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.technlov.home.core.Action
import com.technlov.home.core.Capability
import com.technlov.home.core.SetValue
import com.technlov.home.core.SunClock
import com.technlov.home.core.SunEvent
import com.technlov.home.core.Trigger
import com.technlov.home.core.Value
import com.technlov.home.core.nextTime
import com.technlov.homecontroller.R
import com.technlov.homecontroller.data.DayPreset
import com.technlov.homecontroller.data.sunClock
import com.technlov.homecontroller.ui.LocalWeekend
import com.technlov.homecontroller.ui.appViewModel
import com.technlov.homecontroller.ui.capabilityIcon
import com.technlov.homecontroller.ui.components.ConfirmDialog
import com.technlov.homecontroller.ui.components.DropdownField
import com.technlov.homecontroller.ui.components.GroupCard
import com.technlov.homecontroller.ui.components.IconBadge
import com.technlov.homecontroller.ui.components.MessageEffect
import com.technlov.homecontroller.ui.components.SectionHeader
import com.technlov.homecontroller.ui.device.snapToStep
import com.technlov.homecontroller.ui.formatNumber
import com.technlov.homecontroller.ui.rememberNotificationAccess
import com.technlov.homecontroller.ui.settings.HomeLocationDialog
import com.technlov.homecontroller.ui.unitSuffix
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

private typealias DraftUpdate = ((AutomationDraft) -> AutomationDraft) -> Unit

@Composable
fun AutomationEditorScreen(automationId: String?, onDone: () -> Unit, prefillDevice: String? = null) {
    val vm = appViewModel(key = "automation:${automationId ?: "new"}:${prefillDevice.orEmpty()}") {
        AutomationEditorViewModel(it, automationId, prefillDevice)
    }
    val draftOrNull by vm.draft.collectAsState()
    val devices by vm.devices.collectAsState()
    val homeLocation by vm.homeLocation.collectAsState()
    val sun = remember(homeLocation) { homeLocation.sunClock() }
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(vm.messages, snackbar)
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var pickLocation by rememberSaveable { mutableStateOf(false) }
    val notifications = rememberNotificationAccess()

    val draft = draftOrNull
    val fallbackName = draft?.let { autoName(it, devices) }.orEmpty()
    val update: DraftUpdate = { transform -> vm.update(transform) }
    val save = {
        if (draft != null && draft.actions.any { it is Action.Notify } && !notifications.granted && !notifications.asked) {
            notifications.request()
        }
        vm.save(fallbackName, onDone)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close)) }
                },
                title = {
                    Text(stringResource(if (draft?.isNew != false) R.string.automation_new else R.string.automation_edit))
                },
                actions = {
                    TextButton(onClick = save, enabled = draft?.problem() == null) { Text(stringResource(R.string.action_save)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (draft == null) return@Scaffold
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "name") {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { name -> update { it.copy(name = name) } },
                    label = { Text(stringResource(R.string.automation_name_label)) },
                    placeholder = { Text(fallbackName) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "when") {
                Column {
                    SectionHeader(stringResource(R.string.automation_when))
                    GroupCard(contentPadding = PaddingValues(16.dp)) {
                        TriggerEditor(draft, devices, sun, hasLocation = homeLocation != null, onSetLocation = { pickLocation = true }, update)
                    }
                }
            }
            item(key = "then") { SectionHeader(stringResource(R.string.automation_then)) }
            draft.actions.forEachIndexed { index, action ->
                item(key = "action:$index") {
                    val replace: (Action) -> Unit = { changed ->
                        update { d -> d.copy(actions = d.actions.mapIndexed { i, a -> if (i == index) changed else a }) }
                    }
                    val remove = { update { d -> d.copy(actions = d.actions.filterIndexed { i, _ -> i != index }) } }
                    when (action) {
                        is Action.SetValues -> SetValuesEditor(action, devices, replace, remove)
                        is Action.Notify -> NotifyEditor(action, replace, remove)
                    }
                }
            }
            item(key = "add") {
                Column {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = devices.isNotEmpty(),
                            onClick = {
                                val first = devices.first()
                                update { it.copy(actions = it.actions + Action.SetValues(first.key, defaultChanges(first))) }
                            },
                        ) {
                            Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.automation_add_device_action))
                        }
                        OutlinedButton(onClick = { update { it.copy(actions = it.actions + Action.Notify("", "")) } }) {
                            Icon(Icons.Rounded.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.automation_add_notify_action))
                        }
                    }
                    if (devices.isEmpty()) {
                        Text(
                            stringResource(R.string.automation_no_devices_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                        )
                    }
                }
            }
            item(key = "enabled") {
                GroupCard {
                    SwitchListRow(
                        title = stringResource(R.string.automation_enabled),
                        text = stringResource(R.string.automation_enabled_text),
                        checked = draft.enabled,
                    ) { enabled -> update { it.copy(enabled = enabled) } }
                    HorizontalDivider(
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    SwitchListRow(
                        title = stringResource(R.string.automation_run_once),
                        text = stringResource(R.string.automation_run_once_text),
                        checked = draft.runOnce,
                    ) { once -> update { it.copy(runOnce = once) } }
                }
            }
            draft.problem()?.let { problem ->
                item(key = "problem") {
                    Text(
                        problemText(problem),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            item(key = "tools") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { vm.runNow(fallbackName) }, enabled = draft.problem() == null) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.automation_run_now))
                    }
                    Spacer(Modifier.weight(1f))
                    if (!draft.isNew) {
                        TextButton(
                            onClick = { confirmDelete = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_delete))
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete && draft != null) {
        ConfirmDialog(
            title = stringResource(R.string.automation_delete_title),
            text = stringResource(R.string.automation_delete_text, draft.name.ifBlank { fallbackName }),
            confirmLabel = stringResource(R.string.action_delete),
            icon = Icons.Rounded.Delete,
            onConfirm = {
                confirmDelete = false
                vm.delete(onDone)
            },
            onDismiss = { confirmDelete = false },
        )
    }

    if (pickLocation) {
        HomeLocationDialog(
            current = homeLocation,
            onSave = { location ->
                vm.setHomeLocation(location)
                pickLocation = false
            },
            onDismiss = { pickLocation = false },
        )
    }
}

@Composable
private fun autoName(draft: AutomationDraft, devices: List<DeviceOption>): String = when (draft.kind) {
    TriggerKind.TIME -> stringResource(R.string.automation_auto_name_time, formatTime(draft.hour, draft.minute))
    TriggerKind.SUN -> sunOffsetText(draft.sunEvent, draft.offsetMinutes)
    TriggerKind.DEVICE -> stringResource(
        R.string.automation_auto_name_state,
        devices.firstOrNull { it.key == draft.device }?.name ?: stringResource(R.string.automation_a_device),
    )
}

@Composable
private fun SwitchListRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(text) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun problemText(problem: DraftProblem): String = stringResource(
    when (problem) {
        DraftProblem.NO_TRIGGER_DEVICE -> R.string.problem_trigger_device
        DraftProblem.NO_TRIGGER_VALUE -> R.string.problem_trigger_value
        DraftProblem.NO_ACTIONS -> R.string.problem_no_actions
        DraftProblem.EMPTY_DEVICE_ACTION -> R.string.problem_empty_device_action
        DraftProblem.EMPTY_NOTIFICATION -> R.string.problem_empty_notification
    },
)


// ---- trigger ----

@Composable
private fun TriggerEditor(
    draft: AutomationDraft,
    devices: List<DeviceOption>,
    sun: SunClock,
    hasLocation: Boolean,
    onSetLocation: () -> Unit,
    update: DraftUpdate,
) {
    val timed = draft.kind != TriggerKind.DEVICE
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = timed,
            onClick = { if (!timed) update { it.copy(kind = TriggerKind.TIME) } },
            shape = SegmentedButtonDefaults.itemShape(0, 2),
            icon = { Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(18.dp)) },
        ) { Text(stringResource(R.string.trigger_time)) }
        SegmentedButton(
            selected = !timed,
            onClick = { update { it.copy(kind = TriggerKind.DEVICE) } },
            shape = SegmentedButtonDefaults.itemShape(1, 2),
            icon = { Icon(Icons.Rounded.Sensors, contentDescription = null, modifier = Modifier.size(18.dp)) },
        ) { Text(stringResource(R.string.trigger_device)) }
    }
    Spacer(Modifier.height(16.dp))
    AnimatedContent(
        targetState = timed,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "triggerKind",
    ) { isTimed ->
        Column {
            if (isTimed) TimeTriggerEditor(draft, sun, hasLocation, onSetLocation, update)
            else StateTriggerEditor(draft, devices, update)
        }
    }
}

/** A clock time, or sunrise / sunset with an offset; then the days. */
@Composable
private fun TimeTriggerEditor(
    draft: AutomationDraft,
    sun: SunClock,
    hasLocation: Boolean,
    onSetLocation: () -> Unit,
    update: DraftUpdate,
) {
    val scheme = MaterialTheme.colorScheme
    var picking by rememberSaveable { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = draft.kind == TriggerKind.TIME,
            onClick = { update { it.copy(kind = TriggerKind.TIME) } },
            label = { Text(stringResource(R.string.trigger_at_time)) },
            leadingIcon = { Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        listOf(SunEvent.SUNRISE to R.string.sun_sunrise, SunEvent.SUNSET to R.string.sun_sunset).forEach { (event, label) ->
            FilterChip(
                selected = draft.kind == TriggerKind.SUN && draft.sunEvent == event,
                onClick = { update { it.copy(kind = TriggerKind.SUN, sunEvent = event) } },
                label = { Text(stringResource(label)) },
                leadingIcon = { Icon(Icons.Rounded.WbTwilight, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    if (draft.kind == TriggerKind.SUN) {
        SunTimeEditor(draft, sun, hasLocation, onSetLocation, update)
    } else {
        Surface(
            onClick = { picking = true },
            shape = MaterialTheme.shapes.medium,
            color = scheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Schedule, contentDescription = null, tint = scheme.primary)
                Spacer(Modifier.width(16.dp))
                Text(formatTime(draft.hour, draft.minute), style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.action_change), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.trigger_repeat), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        localWeek().forEach { day ->
            val selected = day in draft.days
            val fullName = day.getDisplayName(TextStyle.FULL, Locale.getDefault())
            Surface(
                checked = selected,
                onCheckedChange = { on -> update { d -> d.copy(days = if (on) d.days + day else d.days - day) } },
                shape = CircleShape,
                color = if (selected) scheme.primary else scheme.surfaceContainerHighest,
                contentColor = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = 48.dp)
                    .aspectRatio(1f)
                    .semantics { contentDescription = fullName },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(day.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    // Quick picks; which days are the weekend follows Settings (or the phone's region).
    val weekend = LocalWeekend.current
    val preset = DayPreset.of(draft.days, weekend)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            DayPreset.EVERY_DAY to R.string.days_every_day,
            DayPreset.WORKDAYS to R.string.days_workdays,
            DayPreset.WEEKEND to R.string.days_weekend,
        ).forEach { (pick, label) ->
            FilterChip(
                selected = preset == pick,
                onClick = { update { it.copy(days = pick.days(weekend)) } },
                label = { Text(stringResource(label)) },
            )
        }
    }
    if (picking) {
        TimePickerDialog(
            hour = draft.hour,
            minute = draft.minute,
            onConfirm = { h, m ->
                update { it.copy(hour = h, minute = m) }
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/** "15 min before sunset" with earlier / later buttons, then when that is next -- or why it can't run yet. */
@Composable
private fun SunTimeEditor(
    draft: AutomationDraft,
    sun: SunClock,
    hasLocation: Boolean,
    onSetLocation: () -> Unit,
    update: DraftUpdate,
) {
    val scheme = MaterialTheme.colorScheme
    val max = Trigger.Sun.MAX_OFFSET_MINUTES
    Surface(shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WbTwilight, contentDescription = null, tint = scheme.primary)
            Spacer(Modifier.width(16.dp))
            Text(
                sunOffsetText(draft.sunEvent, draft.offsetMinutes),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { update { it.withOffsetBy(-SUN_OFFSET_STEP) } },
                enabled = draft.offsetMinutes > -max,
            ) { Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.action_earlier)) }
            IconButton(
                onClick = { update { it.withOffsetBy(SUN_OFFSET_STEP) } },
                enabled = draft.offsetMinutes < max,
            ) { Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.action_later)) }
        }
    }
    Spacer(Modifier.height(8.dp))
    if (!hasLocation) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.LocationOff, contentDescription = null, tint = scheme.error, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.sun_needs_location), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                TextButton(onClick = onSetLocation) { Text(stringResource(R.string.sun_set_location)) }
            }
        }
    } else {
        val next = draft.timedTrigger()?.nextTime(LocalDateTime.now(), sun)
        Text(
            if (next != null) stringResource(R.string.sun_next, whenText(next)) else stringResource(R.string.sun_next_none),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun TimePickerDialog(hour: Int, minute: Int, onConfirm: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = DateFormat.is24HourFormat(context))
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 400.dp),
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.time_picker_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
                TimePicker(state = state)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text(stringResource(R.string.action_ok)) }
                }
            }
        }
    }
}

@Composable
private fun StateTriggerEditor(draft: AutomationDraft, devices: List<DeviceOption>, update: DraftUpdate) {
    if (devices.isEmpty()) {
        Text(
            stringResource(R.string.automation_no_devices_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val device = devices.firstOrNull { it.key == draft.device }
    DropdownField(
        label = stringResource(R.string.automation_device_label),
        options = devices,
        selected = device,
        optionLabel = { it.name },
        onSelect = { d -> update { it.copy(device = d.key, key = null, value = null) } },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    val capability = device?.capability(draft.key)
    DropdownField(
        label = stringResource(R.string.automation_value_label),
        options = device?.capabilities.orEmpty(),
        selected = capability,
        optionLabel = { it.label },
        onSelect = { c ->
            update { it.copy(key = c.key, op = opsFor(c).first(), value = defaultValue(c, device?.values?.get(c.key))) }
        },
        enabled = device != null,
        modifier = Modifier.fillMaxWidth(),
    )
    if (capability != null) {
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            opsFor(capability).forEach { op ->
                FilterChip(
                    selected = draft.op == op,
                    onClick = { update { it.copy(op = op) } },
                    label = { Text(opText(op)) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        ValueEditor(capability, draft.value) { value -> update { it.copy(value = value) } }
    }
}

/** Edits a trigger value to compare against. A blank number clears it (the draft then cannot be saved). */
@Composable
private fun ValueEditor(capability: Capability, value: Value?, onChange: (Value?) -> Unit) {
    when (capability) {
        is Capability.Power, is Capability.Toggle -> OnOffSegments((value as? Value.Bool)?.v ?: true) { onChange(Value.Bool(it)) }
        is Capability.Choice -> DropdownField(
            label = capability.label,
            options = capability.options,
            selected = capability.options.firstOrNull { it.value == (value as? Value.Text)?.v },
            optionLabel = { it.label },
            onSelect = { onChange(Value.Text(it.value)) },
            modifier = Modifier.fillMaxWidth(),
        )
        is Capability.Number, is Capability.Reading -> {
            val unit = (capability as? Capability.Number)?.unit ?: (capability as? Capability.Reading)?.unit.orEmpty()
            var text by remember(capability.key) {
                mutableStateOf(
                    when (value) {
                        is Value.Num -> plainNumber(value.v)
                        is Value.Text -> value.v
                        else -> ""
                    },
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = { typed ->
                    text = typed
                    val number = typed.trim().replace(',', '.').toDoubleOrNull()
                    onChange(
                        when {
                            number != null -> Value.Num(number)
                            typed.isBlank() -> null
                            capability is Capability.Reading -> Value.Text(typed.trim())
                            else -> null
                        },
                    )
                },
                label = { Text(capability.label) },
                suffix = { if (unit.isNotEmpty()) Text(unit) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = if (capability is Capability.Number) KeyboardType.Decimal else KeyboardType.Text),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun plainNumber(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

@Composable
private fun OnOffSegments(on: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(selected = on, onClick = { onChange(true) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
            Text(stringResource(R.string.value_on))
        }
        SegmentedButton(selected = !on, onClick = { onChange(false) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
            Text(stringResource(R.string.value_off))
        }
    }
}

// ---- actions ----

@Composable
private fun ActionFrame(icon: ImageVector, title: String, onRemove: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    GroupCard(contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                container = MaterialTheme.colorScheme.secondaryContainer,
                size = 36.dp,
                iconSize = 20.dp,
            )
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.automation_remove_action))
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.padding(end = 8.dp), content = content)
    }
}

@Composable
private fun SetValuesEditor(action: Action.SetValues, devices: List<DeviceOption>, onChange: (Action) -> Unit, onRemove: () -> Unit) {
    val device = devices.firstOrNull { it.key == action.device }
    ActionFrame(Icons.Rounded.Tune, stringResource(R.string.automation_action_device), onRemove) {
        DropdownField(
            label = stringResource(R.string.automation_device_label),
            options = devices,
            selected = device,
            optionLabel = { it.name },
            onSelect = { d -> onChange(Action.SetValues(d.key, defaultChanges(d))) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (device == null) {
            Text(
                stringResource(R.string.automation_device_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@ActionFrame
        }
        Spacer(Modifier.height(4.dp))
        val order = device.settable.map { it.key }
        device.settable.forEach { capability ->
            ChangeRow(
                capability = capability,
                value = action.changes.firstOrNull { it.key == capability.key }?.value,
                onInclude = { include ->
                    val changes = if (include) {
                        action.changes + SetValue(capability.key, defaultValue(capability, device.values[capability.key]))
                    } else {
                        action.changes.filterNot { it.key == capability.key }
                    }
                    onChange(action.copy(changes = changes.sortedBy { order.indexOf(it.key) }))
                },
                onValue = { v ->
                    onChange(action.copy(changes = action.changes.map { if (it.key == capability.key) SetValue(it.key, v) else it }))
                },
            )
        }
    }
}

@Composable
private fun ChangeRow(capability: Capability, value: Value?, onInclude: (Boolean) -> Unit, onValue: (Value) -> Unit) {
    val included = value != null
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = included, role = Role.Checkbox, onValueChange = onInclude)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = included, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
            Icon(
                capabilityIcon(capability.key),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(capability.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (included) {
                when (capability) {
                    is Capability.Power, is Capability.Toggle ->
                        Switch(checked = (value as? Value.Bool)?.v == true, onCheckedChange = { onValue(Value.Bool(it)) })
                    is Capability.Number -> Stepper(capability, (value as? Value.Num)?.v ?: capability.min, onValue)
                    else -> Unit
                }
            }
        }
        if (included && capability is Capability.Choice) {
            DropdownField(
                label = capability.label,
                options = capability.options,
                selected = capability.options.firstOrNull { it.value == (value as? Value.Text)?.v },
                optionLabel = { it.label },
                onSelect = { onValue(Value.Text(it.value)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 40.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun Stepper(capability: Capability.Number, value: Double, onValue: (Value) -> Unit) {
    val step = capability.step.takeIf { it > 0 } ?: 1.0
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { onValue(Value.Num(snapToStep(value - step, capability.min, capability.max, step))) },
            enabled = value > capability.min,
        ) { Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.action_decrease)) }
        Text(
            formatNumber(value) + unitSuffix(capability.unit),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 52.dp),
        )
        IconButton(
            onClick = { onValue(Value.Num(snapToStep(value + step, capability.min, capability.max, step))) },
            enabled = value < capability.max,
        ) { Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.action_increase)) }
    }
}

@Composable
private fun NotifyEditor(action: Action.Notify, onChange: (Action) -> Unit, onRemove: () -> Unit) {
    ActionFrame(Icons.Rounded.NotificationsActive, stringResource(R.string.automation_action_notify), onRemove) {
        OutlinedTextField(
            value = action.title,
            onValueChange = { onChange(action.copy(title = it)) },
            label = { Text(stringResource(R.string.automation_notify_title)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = action.text,
            onValueChange = { onChange(action.copy(text = it)) },
            label = { Text(stringResource(R.string.automation_notify_text)) },
            minLines = 2,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
