package com.technlov.homecontroller.ui.device

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.technlov.home.core.Capability
import com.technlov.homecontroller.R
import com.technlov.homecontroller.ui.capabilityIcon
import com.technlov.homecontroller.ui.components.DropdownField
import com.technlov.homecontroller.ui.components.GroupCard
import com.technlov.homecontroller.ui.formatNumber
import com.technlov.homecontroller.ui.home.contentOn
import com.technlov.homecontroller.ui.modeColor
import com.technlov.homecontroller.ui.modeIcon
import com.technlov.homecontroller.ui.unitSuffix
import kotlin.math.roundToInt

/** The big round power button under the dial or on a device's hero card. */
@Composable
fun BigPowerButton(on: Boolean, enabled: Boolean, accent: Color, onClick: () -> Unit, size: Dp = 76.dp) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(if (on) accent else scheme.surfaceContainerHighest, label = "bigPower")
    val content by animateColorAsState(if (on) contentOn(accent) else scheme.onSurfaceVariant, label = "bigPowerContent")
    val state = stringResource(if (on) R.string.value_on else R.string.value_off)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = container,
        contentColor = content,
        shadowElevation = if (on) 6.dp else 0.dp,
        modifier = Modifier
            .size(size)
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
                modifier = Modifier.size(size * 0.42f),
            )
        }
    }
}

/** Climate modes as round icon buttons, each in its mode colour when selected. */
@Composable
fun ModeSelector(capability: Capability.Choice, selected: String?, enabled: Boolean, onSelect: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (capability.options.size > 5) Modifier.horizontalScroll(scroll) else Modifier)
            .selectableGroup(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        capability.options.forEach { option ->
            val isSelected = option.value == selected
            val color = modeColor(option.value)
            val container by animateColorAsState(
                if (isSelected) color else scheme.surfaceContainerHighest,
                label = "modeContainer",
            )
            val iconTint by animateColorAsState(
                if (isSelected) contentOn(color) else scheme.onSurfaceVariant,
                label = "modeIcon",
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .selectable(
                        selected = isSelected,
                        enabled = enabled && capability.writable,
                        role = Role.RadioButton,
                        onClick = { onSelect(option.value) },
                    )
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            ) {
                Surface(shape = CircleShape, color = container, modifier = Modifier.size(56.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(modeIcon(option.value), contentDescription = null, tint = iconTint)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    option.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) color else scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A titled card for one control: icon, label, current value on the right. */
@Composable
fun ControlCard(
    icon: ImageVector,
    title: String,
    value: String?,
    content: @Composable () -> Unit,
) {
    GroupCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (value != null) {
                Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/**
 * A choice: segmented buttons for up to three options, chips for up to seven, a drop-down for more (swing
 * positions, input sources...).
 */
@Composable
fun ChoiceControl(capability: Capability.Choice, selected: String?, enabled: Boolean, onSelect: (String) -> Unit) {
    val options = capability.options
    ControlCard(capabilityIcon(capability.key), capability.label, value = null) {
        when {
            options.size <= 3 -> SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option.value == selected,
                        onClick = { onSelect(option.value) },
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    ) { Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            options.size <= 7 -> FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                options.forEach { option ->
                    FilterChip(
                        selected = option.value == selected,
                        onClick = { onSelect(option.value) },
                        enabled = enabled,
                        label = { Text(option.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
            else -> DropdownField(
                label = capability.label,
                options = options,
                selected = options.firstOrNull { it.value == selected },
                optionLabel = { it.label },
                onSelect = { onSelect(it.value) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A switch row (display light, turbo, quiet...). */
@Composable
fun ToggleRow(capability: Capability.Toggle, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val canChange = enabled && capability.writable
    ListItem(
        headlineContent = { Text(capability.label) },
        leadingContent = {
            Icon(
                capabilityIcon(capability.key),
                contentDescription = null,
                tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = canChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.toggleable(value = checked, enabled = canChange, role = Role.Switch, onValueChange = onChange),
    )
}

/** A number other than the climate target (brightness, volume...): a slider that sends when released. */
@Composable
fun NumberControl(capability: Capability.Number, value: Double?, enabled: Boolean, onCommit: (Double) -> Unit) {
    var draft by remember(value) { mutableStateOf<Float?>(null) }
    val shown = draft ?: (value ?: capability.min).toFloat()
    val steps = if (capability.step > 0) ((capability.max - capability.min) / capability.step).roundToInt() - 1 else 0
    ControlCard(
        capabilityIcon(capability.key),
        capability.label,
        formatNumber(shown.toDouble()) + unitSuffix(capability.unit),
    ) {
        Slider(
            value = shown,
            onValueChange = { draft = it },
            onValueChangeFinished = {
                draft?.let { onCommit(snapToStep(it.toDouble(), capability.min, capability.max, capability.step)) }
            },
            valueRange = capability.min.toFloat()..capability.max.toFloat(),
            // Visible stops only when there are few of them; otherwise slide freely and snap on release.
            steps = if (steps in 1..20) steps else 0,
            enabled = enabled && capability.writable,
            colors = SliderDefaults.colors(),
        )
    }
}

/** A read-only value row. */
@Composable
fun ReadingRow(icon: ImageVector, label: String, value: String) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = {
            Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** The collapsible "Diagnostics" card: fault codes, firmware, filter, clock, and where the device is. */
@Composable
fun DiagnosticsCard(rows: List<Pair<String, String>>, icons: List<ImageVector>) {
    var open by rememberSaveable { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    GroupCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { open = !open }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.MonitorHeart, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.device_diagnostics), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = stringResource(if (open) R.string.action_collapse else R.string.action_expand),
                modifier = Modifier.rotate(rotation),
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.padding(bottom = 8.dp)) {
                rows.forEachIndexed { index, (label, value) ->
                    ReadingRow(icons.getOrElse(index) { Icons.Rounded.MonitorHeart }, label, value)
                }
            }
        }
    }
}
