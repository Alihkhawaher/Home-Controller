package com.technlov.homecontroller.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.technlov.home.core.EventLog
import com.technlov.homecontroller.AppContainer
import com.technlov.homecontroller.R
import com.technlov.homecontroller.diag.LogEntry
import com.technlov.homecontroller.diag.ShareLog
import com.technlov.homecontroller.ui.appViewModel
import com.technlov.homecontroller.ui.components.ConfirmDialog
import com.technlov.homecontroller.ui.components.GroupCard
import com.technlov.homecontroller.ui.theme.isDarkScheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticsViewModel(private val container: AppContainer) : ViewModel() {
    private val log = container.eventLog

    /** The entries, refreshed at most a few times a second however fast the log grows. */
    val entries: StateFlow<List<LogEntry>> = log.version
        .map { log.snapshot() }
        .onEach { delay(REFRESH_MS) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), log.snapshot())

    val verbose: StateFlow<Boolean> = container.settings
        .map { it.verboseLogging }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), container.settings.value.verboseLogging)

    fun setVerbose(on: Boolean) {
        viewModelScope.launch { container.repository.updateSettings { it.copy(verboseLogging = on) } }
    }

    fun clear() = log.clear()

    suspend fun shareIntent(context: Context, title: String): Intent = ShareLog.prepare(context, container, title)

    private companion object {
        const val REFRESH_MS = 250L
    }
}

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val vm = appViewModel { DiagnosticsViewModel(it) }
    val entries by vm.entries.collectAsState()
    val verbose by vm.verbose.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var autoScroll by rememberSaveable { mutableStateOf(true) }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }

    val sources = remember(entries) { entries.map { it.source }.distinct().sorted() }
    val shown = remember(entries, source) { if (source == null) entries else entries.filter { it.source == source } }
    val listState = rememberLazyListState()
    LaunchedEffect(shown.size, autoScroll) {
        if (autoScroll && shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex)
    }

    val chooserTitle = stringResource(R.string.diagnostics_share_title)
    val noApp = stringResource(R.string.diagnostics_share_none)
    val share: () -> Unit = {
        scope.launch {
            sharing = true
            try {
                context.startActivity(vm.shareIntent(context, chooserTitle))
            } catch (e: ActivityNotFoundException) {
                snackbar.showSnackbar(noApp)
            } finally {
                sharing = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_diagnostics)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = share, enabled = !sharing) {
                        if (sharing) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.diagnostics_share))
                        }
                    }
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = stringResource(R.string.diagnostics_clear))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                GroupCard {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.diagnostics_verbose)) },
                        supportingContent = { Text(stringResource(R.string.diagnostics_verbose_text)) },
                        trailingContent = { Switch(checked = verbose, onCheckedChange = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.toggleable(value = verbose, role = Role.Switch, onValueChange = vm::setVerbose),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.diagnostics_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = autoScroll,
                    onClick = { autoScroll = !autoScroll },
                    label = { Text(stringResource(R.string.diagnostics_follow)) },
                    leadingIcon = { Icon(Icons.Rounded.VerticalAlignBottom, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                FilterChip(
                    selected = source == null,
                    onClick = { source = null },
                    label = { Text(stringResource(R.string.diagnostics_all_sources)) },
                )
                sources.forEach { s ->
                    FilterChip(selected = source == s, onClick = { source = if (source == s) null else s }, label = { Text(s) })
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            if (shown.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.diagnostics_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(shown) { entry -> LogRow(entry) }
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.diagnostics_clear_title),
            text = stringResource(R.string.diagnostics_clear_text),
            confirmLabel = stringResource(R.string.diagnostics_clear),
            icon = Icons.Rounded.DeleteSweep,
            onConfirm = {
                vm.clear()
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
        )
    }
}

private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT) }

@Composable
private fun levelColor(level: EventLog.Level): Color {
    val scheme = MaterialTheme.colorScheme
    return when (level) {
        EventLog.Level.DEBUG -> scheme.onSurfaceVariant.copy(alpha = 0.75f)
        EventLog.Level.INFO -> scheme.onSurface
        EventLog.Level.WARN -> if (isDarkScheme()) Color(0xFFFFC266) else Color(0xFFA65E00)
        EventLog.Level.ERROR -> scheme.error
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    var open by remember { mutableStateOf(false) }
    val color = levelColor(entry.level)
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
    val tint = when (entry.level) {
        EventLog.Level.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        EventLog.Level.WARN -> color.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(tint)
            .then(if (entry.stackTrace != null) Modifier.clickable { open = !open } else Modifier)
            .padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        Row {
            Text(timeFormat.get()!!.format(Date(entry.timeMillis)), style = mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(entry.level.name.first().toString(), style = mono, color = color, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text(entry.source, style = mono, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            entry.message + if (entry.stackTrace != null && !open) "  [+]" else "",
            style = mono,
            color = color,
        )
        if (open && entry.stackTrace != null) {
            Text(entry.stackTrace, style = mono.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
