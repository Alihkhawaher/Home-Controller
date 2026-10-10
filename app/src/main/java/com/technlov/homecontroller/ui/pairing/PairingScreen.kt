package com.technlov.homecontroller.ui.pairing

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.technlov.home.core.DeviceCategory
import com.technlov.home.core.DeviceKey
import com.technlov.home.core.DeviceRef
import com.technlov.homecontroller.R
import com.technlov.homecontroller.pairing.PairingFailure
import com.technlov.homecontroller.pairing.PairingProgress
import com.technlov.homecontroller.ui.OnResume
import com.technlov.homecontroller.ui.appViewModel
import com.technlov.homecontroller.ui.categoryIcon
import com.technlov.homecontroller.ui.components.GroupCard
import com.technlov.homecontroller.ui.components.IconBadge
import com.technlov.homecontroller.ui.components.PillShape
import com.technlov.homecontroller.ui.components.SectionHeader
import com.technlov.homecontroller.ui.discovery.AddDeviceSheet
import com.technlov.homecontroller.ui.onlineColor
import kotlinx.coroutines.delay

@Composable
fun PairingScreen(onBack: () -> Unit, onOpenDevice: (DeviceKey) -> Unit) {
    val vm = appViewModel { PairingViewModel(it) }
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current

    // The home Wi-Fi password lives only here, in plain remember (never saved state), and is wiped once handed over.
    var password by remember { mutableStateOf("") }
    LaunchedEffect(ui.progress) { if (ui.progress.credentialsGone) password = "" }

    val goBack = { if (!vm.back()) onBack() }
    BackHandler(onBack = goBack)
    OnResume { vm.refreshEnvironment() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pairing_title)) },
                navigationIcon = {
                    IconButton(onClick = goBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LinearProgressIndicator(
                progress = { (ui.stepNumber + 1) / PairingStep.entries.size.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            AnimatedContent(
                targetState = ui.step,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "pairingStep",
                modifier = Modifier.fillMaxSize(),
            ) { step ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .imePadding()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (step) {
                        PairingStep.CHOOSE -> ChooseStep(ui, vm::choose)
                        PairingStep.STEPS -> StepsStep(ui, vm::toHotspots)
                        PairingStep.HOTSPOT -> HotspotStep(ui, vm)
                        PairingStep.HOME_WIFI -> HomeWifiStep(ui, password, { password = it }, vm)
                        PairingStep.PROGRESS -> ProgressStep(
                            ui = ui,
                            onCancel = vm::cancel,
                            onTryAgain = vm::tryAgain,
                            onWaitAnyway = vm::waitAnyway,
                            onAdd = vm::add,
                            onOpenDevice = onOpenDevice,
                            onDone = onBack,
                            onOpenWifiSettings = {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                                } catch (e: ActivityNotFoundException) {
                                    // Nothing to open.
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

// ---- 1. choose the plugin ----

@Composable
private fun ColumnScope.ChooseStep(ui: PairingUi, onChoose: (String) -> Unit) {
    StepTitle(stringResource(R.string.pairing_choose_title), stringResource(R.string.pairing_choose_text))
    if (ui.connectors.isEmpty()) {
        GroupCard(contentPadding = PaddingValues(16.dp)) {
            Text(stringResource(R.string.pairing_none), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    GroupCard {
        ui.connectors.forEach { info ->
            ListItem(
                headlineContent = { Text(info.name) },
                supportingContent = { Text(info.description) },
                leadingContent = {
                    IconBadge(
                        categoryIcon(info.categories.firstOrNull() ?: DeviceCategory.OTHER),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        container = MaterialTheme.colorScheme.primaryContainer,
                    )
                },
                trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickableRow { onChoose(info.id) },
            )
        }
    }
}

// ---- 2. setup mode ----

@Composable
private fun ColumnScope.StepsStep(ui: PairingUi, onReady: () -> Unit) {
    StepTitle(ui.title ?: stringResource(R.string.pairing_title), stringResource(R.string.pairing_steps_text))
    GroupCard(contentPadding = PaddingValues(16.dp)) {
        ui.setupSteps.forEachIndexed { index, text ->
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            (index + 1).toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
    TipCard(Icons.Rounded.Timer, stringResource(R.string.pairing_tip_time))
    Spacer(Modifier.height(4.dp))
    Button(onClick = onReady, shape = PillShape, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text(stringResource(R.string.pairing_ready), style = MaterialTheme.typography.titleMedium)
    }
}

// ---- 3. find the hotspot ----

@Composable
private fun ColumnScope.HotspotStep(ui: PairingUi, vm: PairingViewModel) {
    val context = LocalContext.current
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.scan()
    }
    var manual by rememberSaveable { mutableStateOf("") }

    StepTitle(stringResource(R.string.pairing_hotspot_title), stringResource(R.string.pairing_hotspot_text))

    if (!ui.canScan) {
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.WifiFind, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.pairing_permission_title), style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.pairing_permission_text), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { askPermission.launch(vm.scanPermissions) }, shape = PillShape) {
                    Text(stringResource(R.string.action_allow))
                }
            }
        }
    } else {
        if (!ui.locationOn) {
            WarningCard(Icons.Rounded.LocationOff, stringResource(R.string.pairing_location_off)) {
                TextButton(onClick = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    } catch (e: ActivityNotFoundException) {
                        // Nothing to open.
                    }
                }) { Text(stringResource(R.string.action_open_settings)) }
            }
        }
        SectionHeader(stringResource(R.string.pairing_hotspots_nearby)) {
            if (ui.scanning) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = vm::scan) {
                    Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.action_scan_again))
                }
            }
        }
        GroupCard {
            if (ui.hotspots.isEmpty()) {
                ListItem(
                    headlineContent = {
                        Text(stringResource(if (ui.scanning) R.string.pairing_hotspots_scanning else R.string.pairing_hotspots_none))
                    },
                    supportingContent = if (ui.scanning) null else {
                        { Text(stringResource(if (ui.throttled) R.string.pairing_hotspots_throttled else R.string.pairing_hotspots_none_hint)) }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            } else {
                ui.hotspots.forEach { spot ->
                    ListItem(
                        headlineContent = { Text(spot.ssid, fontWeight = FontWeight.Medium) },
                        supportingContent = { Text(stringResource(R.string.pairing_signal, signalWord(spot.level))) },
                        leadingContent = { Icon(Icons.Rounded.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickableRow { vm.pickHotspot(spot.ssid) },
                    )
                }
            }
        }
    }

    SectionHeader(stringResource(R.string.pairing_manual_title))
    GroupCard(contentPadding = PaddingValues(16.dp)) {
        Text(
            stringResource(R.string.pairing_manual_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        val unusual = manual.isNotBlank() && !vm.looksLikeSetupHotspot(manual)
        OutlinedTextField(
            value = manual,
            onValueChange = { manual = it.take(32) },
            label = { Text(stringResource(R.string.pairing_manual_label)) },
            singleLine = true,
            supportingText = if (unusual) {
                { Text(stringResource(R.string.pairing_manual_unusual)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(autoCorrect = false, imeAction = ImeAction.Done),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = { vm.pickHotspot(manual) }, enabled = manual.isNotBlank()) {
            Text(stringResource(R.string.pairing_manual_use))
        }
    }
}

@Composable
private fun signalWord(level: Int): String = stringResource(
    when {
        level >= -55 -> R.string.signal_strong
        level >= -70 -> R.string.signal_good
        else -> R.string.signal_weak
    },
)

// ---- 4. home Wi-Fi ----

@Composable
private fun ColumnScope.HomeWifiStep(ui: PairingUi, password: String, onPassword: (String) -> Unit, vm: PairingViewModel) {
    var show by remember { mutableStateOf(false) }
    StepTitle(
        stringResource(R.string.pairing_home_title),
        stringResource(R.string.pairing_home_text, ui.setupSsid.orEmpty()),
    )
    if (ui.fiveGhzOnly) {
        WarningCard(Icons.Rounded.Warning, stringResource(R.string.pairing_5ghz_warning))
    }
    GroupCard(contentPadding = PaddingValues(16.dp)) {
        OutlinedTextField(
            value = ui.homeSsid,
            onValueChange = vm::setHomeSsid,
            label = { Text(stringResource(R.string.pairing_home_ssid)) },
            leadingIcon = { Icon(Icons.Rounded.Wifi, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrect = false, imeAction = ImeAction.Next),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPassword,
            label = { Text(stringResource(R.string.pairing_home_password)) },
            leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { show = !show }) {
                    Icon(
                        if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = stringResource(if (show) R.string.pairing_hide_password else R.string.pairing_show_password),
                    )
                }
            },
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrect = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        if (ui.needs24GHz) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.pairing_24ghz_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    TipCard(Icons.Rounded.Lock, stringResource(R.string.pairing_password_note))
    Button(
        onClick = { vm.start(password) },
        enabled = ui.homeSsid.isNotBlank(),
        shape = PillShape,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Text(stringResource(R.string.pairing_connect), style = MaterialTheme.typography.titleMedium)
    }
}

// ---- 5. progress ----

private enum class StageState { PENDING, ACTIVE, DONE, FAILED }

@Composable
private fun ColumnScope.ProgressStep(
    ui: PairingUi,
    onCancel: () -> Unit,
    onTryAgain: () -> Unit,
    onWaitAnyway: () -> Unit,
    onAdd: (DeviceRef, String, String?, String?) -> Unit,
    onOpenDevice: (DeviceKey) -> Unit,
    onDone: () -> Unit,
    onOpenWifiSettings: () -> Unit,
) {
    val progress = ui.progress
    val stage = when (progress) {
        PairingProgress.Idle, is PairingProgress.Joining -> 0
        PairingProgress.Sending -> 1
        is PairingProgress.Waiting -> 2
        is PairingProgress.Found -> 3
        is PairingProgress.Failed -> when (progress.reason) {
            PairingFailure.JOIN_FAILED -> 0
            PairingFailure.SEND_FAILED, PairingFailure.NOT_CONFIRMED -> 1
            PairingFailure.NOT_FOUND -> 2
        }
        PairingProgress.Cancelled -> -1
    }
    val failed = progress is PairingProgress.Failed

    StepTitle(stringResource(R.string.pairing_progress_title), ui.setupSsid?.let { stringResource(R.string.pairing_progress_text, it) })
    GroupCard(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.animateContentSize()) {
        val labels = listOf(
            R.string.pairing_stage_join,
            R.string.pairing_stage_send,
            R.string.pairing_stage_wait,
            R.string.pairing_stage_done,
        )
        labels.forEachIndexed { index, label ->
            val state = when {
                stage < 0 -> StageState.PENDING
                index < stage -> StageState.DONE
                index == stage && failed -> StageState.FAILED
                index == stage && index == 3 -> StageState.DONE
                index == stage -> StageState.ACTIVE
                else -> StageState.PENDING
            }
            StageRow(stringResource(label), state) {
                if (index == 0 && progress is PairingProgress.Joining && progress.manual) {
                    Text(
                        stringResource(
                            R.string.pairing_manual_join,
                            progress.hotspot,
                            ui.setupHotspotPassword ?: stringResource(R.string.pairing_no_password),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onOpenWifiSettings) { Text(stringResource(R.string.action_open_wifi_settings)) }
                }
                if (index == 0 && progress is PairingProgress.Joining && !progress.manual) {
                    Text(
                        stringResource(R.string.pairing_join_prompt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (index == 2 && progress is PairingProgress.Waiting) WaitingBar(progress)
            }
        }
    }

    when (progress) {
        is PairingProgress.Failed -> FailureCard(progress, onTryAgain, onWaitAnyway)
        PairingProgress.Cancelled -> {
            Text(stringResource(R.string.pairing_cancelled), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onTryAgain, shape = PillShape) { Text(stringResource(R.string.action_try_again)) }
        }
        is PairingProgress.Found -> FoundCard(ui, progress, onAdd, onOpenDevice, onDone)
        else -> OutlinedButton(onClick = onCancel, shape = PillShape, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

@Composable
private fun StageRow(label: String, state: StageState, extra: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (state) {
                StageState.ACTIVE -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                StageState.DONE -> Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = onlineColor())
                StageState.FAILED -> Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = scheme.error)
                StageState.PENDING -> Surface(
                    shape = CircleShape,
                    color = Color.Transparent,
                    border = BorderStroke(2.dp, scheme.outlineVariant),
                    modifier = Modifier.size(18.dp),
                ) {}
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (state == StageState.ACTIVE) FontWeight.SemiBold else FontWeight.Normal,
                color = if (state == StageState.PENDING) scheme.onSurfaceVariant else scheme.onSurface,
            )
            extra()
        }
    }
}

@Composable
private fun WaitingBar(waiting: PairingProgress.Waiting) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(waiting.startedAtMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val fraction = ((now - waiting.startedAtMillis).toFloat() / waiting.timeoutMs).coerceIn(0f, 1f)
    Spacer(Modifier.height(6.dp))
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(4.dp))
    Text(
        pluralStringResource(R.plurals.pairing_searches, waiting.searches, waiting.searches),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FailureCard(failure: PairingProgress.Failed, onTryAgain: () -> Unit, onWaitAnyway: () -> Unit) {
    val (title, help) = when (failure.reason) {
        PairingFailure.JOIN_FAILED -> R.string.pairing_fail_join to R.string.pairing_fail_join_help
        PairingFailure.SEND_FAILED -> R.string.pairing_fail_send to R.string.pairing_fail_send_help
        PairingFailure.NOT_CONFIRMED -> R.string.pairing_fail_unconfirmed to R.string.pairing_fail_unconfirmed_help
        PairingFailure.NOT_FOUND -> R.string.pairing_fail_not_found to R.string.pairing_fail_not_found_help
    }
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(help), style = MaterialTheme.typography.bodyMedium)
            failure.detail?.takeIf { it.isNotBlank() && failure.reason == PairingFailure.NOT_CONFIRMED }?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onTryAgain, shape = PillShape) { Text(stringResource(R.string.action_try_again)) }
        if (failure.reason == PairingFailure.NOT_CONFIRMED || failure.reason == PairingFailure.NOT_FOUND) {
            OutlinedButton(onClick = onWaitAnyway, shape = PillShape) {
                Text(
                    stringResource(
                        if (failure.reason == PairingFailure.NOT_FOUND) R.string.pairing_search_again else R.string.pairing_wait_anyway,
                    ),
                )
            }
        }
    }
}

@Composable
private fun FoundCard(
    ui: PairingUi,
    found: PairingProgress.Found,
    onAdd: (DeviceRef, String, String?, String?) -> Unit,
    onOpenDevice: (DeviceKey) -> Unit,
    onDone: () -> Unit,
) {
    var adding by remember(found.device.id) { mutableStateOf(ui.addedKey == null) }
    val added = ui.addedKey
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            IconBadge(
                categoryIcon(found.device.category),
                tint = MaterialTheme.colorScheme.onPrimary,
                container = MaterialTheme.colorScheme.primary,
                size = 72.dp,
                iconSize = 36.dp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(if (added == null) R.string.pairing_found else R.string.pairing_added),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                listOfNotNull(found.device.name, found.device.address).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
    if (added == null) {
        Button(onClick = { adding = true }, shape = PillShape, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(stringResource(R.string.add_sheet_confirm), style = MaterialTheme.typography.titleMedium)
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onOpenDevice(added) }, shape = PillShape) { Text(stringResource(R.string.pairing_open_device)) }
            OutlinedButton(onClick = onDone, shape = PillShape) { Text(stringResource(R.string.pairing_done)) }
        }
    }
    if (adding && added == null) {
        AddDeviceSheet(
            ref = found.device,
            rooms = ui.rooms,
            onDismiss = { adding = false },
            onAdd = { name, roomId, newRoom ->
                onAdd(found.device, name, roomId, newRoom)
                adding = false
            },
        )
    }
}

// ---- shared bits ----

@Composable
private fun StepTitle(title: String, text: String?) {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (text != null) {
            Spacer(Modifier.height(6.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TipCard(icon: ImageVector, text: String) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun WarningCard(icon: ImageVector, text: String, action: (@Composable () -> Unit)? = null) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            action?.invoke()
        }
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = clickable(role = Role.Button, onClick = onClick)
