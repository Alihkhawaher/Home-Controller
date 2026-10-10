package com.technlov.homecontroller.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.technlov.home.core.ConnectorInfo
import com.technlov.home.core.DeviceCategory
import com.technlov.homecontroller.BuildConfig
import com.technlov.homecontroller.R
import com.technlov.homecontroller.app
import com.technlov.homecontroller.ui.categoryIcon
import com.technlov.homecontroller.ui.components.GroupCard
import com.technlov.homecontroller.ui.components.IconBadge
import com.technlov.homecontroller.ui.components.SectionHeader
import com.technlov.homecontroller.ui.components.StatusPill

private const val REPO_URL = "https://github.com/Alihkhawaher/Home-Controller"
private const val LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0"
private const val GREECLIMATE_URL = "https://github.com/cmroche/greeclimate"
private const val GREE_REMOTE_URL = "https://github.com/tomikaa87/gree-remote"
private const val SUNCALC_URL = "https://github.com/shred/commons-suncalc"

private fun UriHandler.openSafely(url: String) {
    try {
        openUri(url)
    } catch (e: Exception) {
        // No browser on this device; nothing to do.
    }
}

@Composable
private fun BackBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
    )
}

private fun screenPadding(padding: PaddingValues) = PaddingValues(
    start = 16.dp,
    end = 16.dp,
    top = padding.calculateTopPadding() + 4.dp,
    bottom = padding.calculateBottomPadding() + 32.dp,
)

// ---- plugins ----

@Composable
fun PluginsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val connectors = remember { context.app.registry.all.map { it.info } }
    val uri = LocalUriHandler.current
    Scaffold(topBar = { BackBar(stringResource(R.string.settings_plugins), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = screenPadding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "intro") {
                Text(
                    stringResource(R.string.plugins_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            items(connectors, key = { it.id }) { info -> PluginCard(info) { uri.openSafely(it) } }
            item(key = "more") { ComingSoonCard { uri.openSafely(REPO_URL) } }
        }
    }
}

@Composable
private fun categoryName(category: DeviceCategory): String = stringResource(
    when (category) {
        DeviceCategory.CLIMATE -> R.string.category_climate
        DeviceCategory.TV -> R.string.category_tv
        DeviceCategory.SWITCH -> R.string.category_switch
        DeviceCategory.LIGHT -> R.string.category_light
        DeviceCategory.SENSOR -> R.string.category_sensor
        DeviceCategory.MEDIA -> R.string.category_media
        DeviceCategory.OTHER -> R.string.category_other
    },
)

@Composable
private fun PluginCard(info: ConnectorInfo, onOpen: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    GroupCard(contentPadding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                categoryIcon(info.categories.firstOrNull() ?: DeviceCategory.OTHER),
                tint = scheme.onPrimaryContainer,
                container = scheme.primaryContainer,
                size = 52.dp,
                iconSize = 28.dp,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(info.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.plugin_version, info.version),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            StatusPill(
                stringResource(R.string.plugin_built_in),
                container = scheme.secondaryContainer,
                content = scheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(info.description, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lan, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(info.protocol, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            info.categories.forEach { category ->
                StatusPill(
                    categoryName(category),
                    icon = categoryIcon(category),
                    container = scheme.surfaceContainerHighest,
                    content = scheme.onSurfaceVariant,
                )
            }
        }
        info.homepage?.let { page ->
            TextButton(onClick = { onOpen(page) }) {
                Text(stringResource(R.string.plugin_homepage))
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun ComingSoonCard(onContribute: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.plugins_more_title), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.plugins_more_text), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onContribute) {
                Text(stringResource(R.string.plugins_contribute), color = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

// ---- about ----

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val icon = remember {
        runCatching { ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap(288, 288)?.asImageBitmap() }.getOrNull()
    }
    Scaffold(topBar = { BackBar(stringResource(R.string.settings_about), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = screenPadding(padding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "hero") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (icon != null) {
                        Image(
                            icon,
                            contentDescription = null,
                            modifier = Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(28.dp)),
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        stringResource(R.string.about_version_code, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.about_tagline),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            item(key = "links") {
                GroupCard {
                    LinkRow(Icons.Rounded.Code, stringResource(R.string.about_source), stringResource(R.string.about_source_text)) {
                        uri.openSafely(REPO_URL)
                    }
                    LinkRow(Icons.Rounded.Gavel, stringResource(R.string.about_license), stringResource(R.string.about_license_text)) {
                        uri.openSafely(LICENSE_URL)
                    }
                    LinkRow(Icons.Rounded.PrivacyTip, stringResource(R.string.about_privacy), stringResource(R.string.about_privacy_text), null)
                }
            }
            item(key = "creditsHeader") { SectionHeader(stringResource(R.string.about_credits)) }
            item(key = "credits") {
                GroupCard {
                    LinkRow(Icons.Rounded.Handshake, "greeclimate", stringResource(R.string.about_credit_greeclimate)) {
                        uri.openSafely(GREECLIMATE_URL)
                    }
                    LinkRow(Icons.Rounded.Handshake, "gree-remote", stringResource(R.string.about_credit_gree_remote)) {
                        uri.openSafely(GREE_REMOTE_URL)
                    }
                    LinkRow(Icons.Rounded.Handshake, "commons-suncalc", stringResource(R.string.about_credit_suncalc)) {
                        uri.openSafely(SUNCALC_URL)
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkRow(icon: ImageVector, title: String, text: String, onClick: (() -> Unit)?) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(text) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = onClick?.let { { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp)) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier,
    )
}
