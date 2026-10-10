package com.technlov.homecontroller.ui.discovery

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.technlov.home.core.DeviceKey
import com.technlov.home.core.DeviceRef
import com.technlov.home.core.key
import com.technlov.homecontroller.R
import com.technlov.homecontroller.data.Room
import com.technlov.homecontroller.net.Ipv4
import com.technlov.homecontroller.ui.appViewModel
import com.technlov.homecontroller.ui.asString
import com.technlov.homecontroller.ui.categoryIcon
import com.technlov.homecontroller.ui.components.GroupCard
import com.technlov.homecontroller.ui.components.IconBadge
import com.technlov.homecontroller.ui.components.MessageEffect
import com.technlov.homecontroller.ui.components.PillShape
import com.technlov.homecontroller.ui.components.RadarPulse
import kotlinx.coroutines.launch

@Composable
fun DiscoveryScreen(onBack: () -> Unit, onOpenDevice: (DeviceKey) -> Unit, onPairDevice: (() -> Unit)?) {
    val vm = appViewModel { DiscoveryViewModel(it) }
    val ui by vm.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(vm.messages, snackbar)
    var adding by remember { mutableStateOf<DeviceRef?>(null) }

    // Start looking straight away the first time the screen opens.
    LaunchedEffect(Unit) { if (!ui.hasScanned) vm.scan() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.discovery_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = vm::scan, enabled = !ui.scanning) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.action_scan_again))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "hero") { ScanHero(ui, onScan = vm::scan) }
            ui.scans.forEach { scan ->
                if (scan.status != ScanStatus.IDLE) {
                    item(key = "connector:${scan.info.id}") {
                        ConnectorResults(
                            scan = scan,
                            added = ui.addedKeys,
                            onAdd = { adding = it },
                            onOpen = { onOpenDevice(it.key()) },
                        )
                    }
                }
            }
            if (onPairDevice != null) {
                item(key = "pair") { PairEntryCard(onPairDevice) }
            }
            item(key = "byip") {
                AddByIpCard(ui, onLookUp = vm::lookUp, onEdit = vm::clearLookupIssue)
            }
        }
    }

    adding?.let { ref ->
        AddDeviceSheet(
            ref = ref,
            rooms = ui.rooms,
            onDismiss = { adding = null },
            onAdd = { name, roomId, newRoom ->
                vm.add(ref, name, roomId, newRoom)
                adding = null
            },
        )
    }
}

@Composable
private fun ScanHero(ui: DiscoveryUi, onScan: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .animateContentSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(176.dp), contentAlignment = Alignment.Center) {
                RadarPulse(color = if (ui.noNetwork) scheme.error else scheme.primary, active = ui.scanning, modifier = Modifier.fillMaxSize())
                Surface(
                    shape = CircleShape,
                    color = if (ui.noNetwork) scheme.error else scheme.primary,
                    shadowElevation = 4.dp,
                    modifier = Modifier.size(68.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (ui.noNetwork) Icons.Rounded.WifiOff else if (ui.scanning) Icons.Rounded.Radar else Icons.Rounded.Wifi,
                            contentDescription = null,
                            tint = if (ui.noNetwork) scheme.onError else scheme.onPrimary,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                when {
                    ui.noNetwork -> stringResource(R.string.discovery_no_wifi_title)
                    ui.scanning -> stringResource(R.string.discovery_scanning_title)
                    ui.hasScanned && ui.foundCount == 0 -> stringResource(R.string.discovery_found_none)
                    ui.hasScanned -> pluralStringResource(R.plurals.discovery_found, ui.foundCount, ui.foundCount)
                    else -> stringResource(R.string.discovery_idle_title)
                },
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    ui.noNetwork -> stringResource(R.string.discovery_no_wifi_text)
                    ui.network != null && ui.scanning -> stringResource(R.string.discovery_scanning_text, ui.network)
                    ui.network != null -> stringResource(R.string.discovery_network_text, ui.network)
                    else -> stringResource(R.string.discovery_idle_text)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            if (ui.noNetwork) {
                OutlinedButton(
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                        } catch (e: ActivityNotFoundException) {
                            // No Wi-Fi settings screen on this device; the text already says what to do.
                        }
                    },
                    shape = PillShape,
                ) { Text(stringResource(R.string.action_open_wifi_settings)) }
                Spacer(Modifier.height(8.dp))
            }
            Button(onClick = onScan, enabled = !ui.scanning, shape = PillShape, modifier = Modifier.height(52.dp)) {
                if (ui.scanning) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = scheme.onPrimary)
                } else {
                    Icon(if (ui.hasScanned) Icons.Rounded.Refresh else Icons.Rounded.Radar, contentDescription = null)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(
                        when {
                            ui.scanning -> R.string.discovery_scanning_button
                            ui.hasScanned -> R.string.action_scan_again
                            else -> R.string.action_scan
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun ConnectorResults(
    scan: ConnectorScan,
    added: Set<DeviceKey>,
    onAdd: (DeviceRef) -> Unit,
    onOpen: (DeviceRef) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.animateContentSize()) {
        Row(
            Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                scan.info.name,
                style = MaterialTheme.typography.titleSmall,
                color = scheme.primary,
                modifier = Modifier.weight(1f),
            )
            when (scan.status) {
                ScanStatus.SCANNING -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                ScanStatus.DONE -> Text(
                    pluralStringResource(R.plurals.discovery_found_short, scan.found.size, scan.found.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                )
                ScanStatus.FAILED -> Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = scheme.error, modifier = Modifier.size(18.dp))
                ScanStatus.IDLE -> Unit
            }
        }
        GroupCard {
            when {
                scan.status == ScanStatus.FAILED -> ListItem(
                    headlineContent = { Text(stringResource(R.string.discovery_connector_failed)) },
                    supportingContent = { Text(scan.issue?.asString() ?: stringResource(R.string.issue_generic)) },
                    leadingContent = { Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = scheme.error) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                scan.found.isEmpty() && scan.status == ScanStatus.SCANNING -> ListItem(
                    headlineContent = { Text(stringResource(R.string.discovery_connector_searching)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                scan.found.isEmpty() -> ListItem(
                    headlineContent = { Text(stringResource(R.string.discovery_connector_none)) },
                    supportingContent = { Text(stringResource(R.string.discovery_connector_none_hint)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                else -> scan.found.forEachIndexed { index, ref ->
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = scheme.outlineVariant.copy(alpha = 0.5f))
                    FoundDeviceRow(ref, ref.key() in added, onAdd = { onAdd(ref) }, onOpen = { onOpen(ref) })
                }
            }
        }
    }
}

@Composable
private fun FoundDeviceRow(ref: DeviceRef, added: Boolean, onAdd: () -> Unit, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val details = listOfNotNull(
        ref.address,
        ref.model,
        ref.firmware?.let { stringResource(R.string.discovery_firmware, it) },
    ).joinToString(" · ")
    ListItem(
        headlineContent = { Text(ref.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(details, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            IconBadge(
                categoryIcon(ref.category),
                tint = scheme.onPrimaryContainer,
                container = scheme.primaryContainer,
                size = 44.dp,
            )
        },
        trailingContent = {
            if (added) {
                AssistChip(
                    onClick = onOpen,
                    label = { Text(stringResource(R.string.discovery_added)) },
                    leadingIcon = { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    colors = AssistChipDefaults.assistChipColors(leadingIconContentColor = scheme.primary),
                )
            } else {
                FilledTonalButton(onClick = onAdd) { Text(stringResource(R.string.action_add)) }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** "Set up a new device": for devices that are not on the Wi-Fi yet. */
@Composable
private fun PairEntryCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AddCircle, contentDescription = null)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.pairing_entry_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.pairing_entry_text), style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun AddByIpCard(ui: DiscoveryUi, onLookUp: (String) -> Unit, onEdit: () -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    val valid = Ipv4.isValid(address)
    GroupCard(contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lan, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.discovery_ip_title), style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.discovery_ip_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = address,
                onValueChange = {
                    address = it.filter { c -> c.isDigit() || c == '.' }.take(15)
                    onEdit()
                },
                label = { Text(stringResource(R.string.discovery_ip_label)) },
                placeholder = { Text(stringResource(R.string.discovery_ip_placeholder)) },
                singleLine = true,
                isError = ui.lookupIssue != null,
                supportingText = ui.lookupIssue?.let { { Text(it.asString()) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (valid) onLookUp(address) }),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            FilledTonalButton(
                onClick = { onLookUp(address) },
                enabled = valid && !ui.lookingUp,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .height(52.dp),
            ) {
                if (ui.lookingUp) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.action_look_up))
            }
        }
    }
}

/** Name the device and pick (or create) its room. */
@Composable
internal fun AddDeviceSheet(
    ref: DeviceRef,
    rooms: List<Room>,
    onDismiss: () -> Unit,
    onAdd: (name: String, roomId: String?, newRoom: String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val suggestions = stringArrayResource(R.array.room_suggestions)
        .filter { s -> rooms.none { it.name.equals(s, ignoreCase = true) } }

    var name by rememberSaveable(ref.key()) { mutableStateOf(ref.name) }
    // "none", "room:<id>", "new:<suggested name>" or "custom".
    var choice by rememberSaveable(ref.key()) { mutableStateOf(rooms.firstOrNull()?.let { "room:${it.id}" } ?: "none") }
    var custom by rememberSaveable(ref.key()) { mutableStateOf("") }

    val roomId = choice.removePrefix("room:").takeIf { choice.startsWith("room:") }
    val newRoom = when {
        choice.startsWith("new:") -> choice.removePrefix("new:")
        choice == "custom" -> custom.trim()
        else -> null
    }
    val canAdd = name.isNotBlank() && (choice != "custom" || custom.isNotBlank())

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        windowInsets = WindowInsets(0),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    categoryIcon(ref.category),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    container = MaterialTheme.colorScheme.primaryContainer,
                    size = 52.dp,
                    iconSize = 28.dp,
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(stringResource(R.string.add_sheet_title), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        listOfNotNull(ref.address, ref.model).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.device_name_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.add_sheet_room), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rooms.forEach { room ->
                    FilterChip(
                        selected = choice == "room:${room.id}",
                        onClick = { choice = "room:${room.id}" },
                        label = { Text(room.name) },
                    )
                }
                suggestions.forEach { suggestion ->
                    FilterChip(
                        selected = choice == "new:$suggestion",
                        onClick = { choice = "new:$suggestion" },
                        label = { Text(suggestion) },
                    )
                }
                FilterChip(
                    selected = choice == "custom",
                    onClick = { choice = "custom" },
                    label = { Text(stringResource(R.string.room_new_chip)) },
                    leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                FilterChip(
                    selected = choice == "none",
                    onClick = { choice = "none" },
                    label = { Text(stringResource(R.string.room_none)) },
                )
            }
            AnimatedVisibility(visible = choice == "custom") {
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text(stringResource(R.string.room_name_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onAdd(name, roomId, newRoom) }
                },
                enabled = canAdd,
                shape = PillShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_sheet_confirm), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
