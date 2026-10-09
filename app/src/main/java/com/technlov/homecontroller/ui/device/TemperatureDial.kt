package com.technlov.homecontroller.ui.device

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.technlov.homecontroller.R
import com.technlov.homecontroller.ui.formatNumber
import com.technlov.homecontroller.ui.theme.NumberStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val STROKE: Dp = 20.dp
private val TICK_SPACE: Dp = 16.dp

/**
 * A climate dial drawn on a Canvas: a flat arc track, the active arc in the mode's colour, a tick per step, a thumb
 * to drag (with a haptic tick per step), the target in big numbers and the room temperature under it.
 * Touches only start a drag on the ring itself, so the page still scrolls when swiped over the middle.
 */
@Composable
fun TemperatureDial(
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    color: Color,
    active: Boolean,
    enabled: Boolean,
    topLabel: String,
    current: Double?,
    unit: String,
    onValueChange: (Double) -> Unit,
    onDragChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val view = LocalView.current
    val arcColor by animateColorAsState(if (active) color else scheme.outline, tween(450), label = "dialArc")
    val trackColor = scheme.surfaceContainerHighest
    val tickColor = scheme.outlineVariant
    val thumbFill = scheme.surface

    var dragging by remember { mutableStateOf(false) }
    val span = (max - min).takeIf { it > 0 } ?: 1.0
    val fraction = ((value - min) / span).coerceIn(0.0, 1.0).toFloat()
    val animated by animateFloatAsState(
        fraction,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "dialFraction",
    )
    val shown = if (dragging) fraction else animated

    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onValueChange)
    val latestOnDrag by rememberUpdatedState(onDragChange)

    val description = stringResource(R.string.dial_description)
    val stateText = formatNumber(value) + unit

    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(1f)
            .semantics {
                contentDescription = description
                stateDescription = stateText
                progressBarRangeInfo = ProgressBarRangeInfo(
                    value.toFloat(),
                    min.toFloat()..max.toFloat(),
                    steps = ((max - min) / step).roundToInt().coerceAtLeast(1) - 1,
                )
                if (enabled) {
                    setProgress { target ->
                        latestOnChange(snapToStep(target.toDouble(), min, max, step))
                        true
                    }
                }
            }
            .pointerInput(enabled, min, max, step) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val radius = ringRadius(size, STROKE.toPx(), TICK_SPACE.toPx())
                    val centre = Offset(size.width / 2f, size.height / 2f)
                    val distance = (down.position - centre).getDistance()
                    if (abs(distance - radius) > STROKE.toPx() * 1.6f) return@awaitEachGesture
                    down.consume()
                    dragging = true
                    latestOnDrag(true)
                    var last = latestValue
                    fun update(position: Offset) {
                        val v = dialValueAt(position.x - centre.x, position.y - centre.y, min, max, step)
                        if (v != last) {
                            last = v
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            latestOnChange(v)
                        }
                    }
                    try {
                        update(down.position)
                        drag(down.id) { change ->
                            change.consume()
                            update(change.position)
                        }
                    } finally {
                        dragging = false
                        latestOnDrag(false)
                    }
                }
            },
    ) {
        val dialSize = maxWidth
        Canvas(Modifier.fillMaxSize()) {
            val strokePx = STROKE.toPx()
            val radius = ringRadius(IntSize(size.width.toInt(), size.height.toInt()), strokePx, TICK_SPACE.toPx())
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2, radius * 2)

            // A tick per step outside the ring; every fifth (whole) degree is longer.
            val steps = ((max - min) / step).roundToInt()
            if (steps in 1..200) {
                for (i in 0..steps) {
                    val f = i / steps.toFloat()
                    val rad = Math.toRadians((DIAL_START_ANGLE + DIAL_SWEEP * f).toDouble())
                    val degrees = min + i * step
                    val major = abs(degrees % 5.0) < 1e-6
                    val inner = radius + strokePx / 2 + 4.dp.toPx()
                    val outer = inner + if (major) 9.dp.toPx() else 5.dp.toPx()
                    val dx = cos(rad).toFloat()
                    val dy = sin(rad).toFloat()
                    drawLine(
                        color = if (f <= shown + 1e-4f) arcColor.copy(alpha = 0.7f) else tickColor,
                        start = Offset(center.x + dx * inner, center.y + dy * inner),
                        end = Offset(center.x + dx * outer, center.y + dy * outer),
                        strokeWidth = if (major) 2.dp.toPx() else 1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }

            drawArc(trackColor, DIAL_START_ANGLE, DIAL_SWEEP, false, topLeft, arcSize, style = Stroke(strokePx, cap = StrokeCap.Round))
            if (shown > 0f) {
                drawArc(arcColor, DIAL_START_ANGLE, DIAL_SWEEP * shown, false, topLeft, arcSize, style = Stroke(strokePx, cap = StrokeCap.Round))
            }

            val thumbRad = Math.toRadians((DIAL_START_ANGLE + DIAL_SWEEP * shown).toDouble())
            val thumb = Offset(center.x + cos(thumbRad).toFloat() * radius, center.y + sin(thumbRad).toFloat() * radius)
            if (dragging) drawCircle(arcColor.copy(alpha = 0.18f), radius = strokePx * 1.5f, center = thumb)
            drawCircle(Color.Black.copy(alpha = 0.14f), radius = strokePx * 0.82f, center = thumb + Offset(0f, 1.5.dp.toPx()))
            drawCircle(thumbFill, radius = strokePx * 0.8f, center = thumb)
            drawCircle(arcColor, radius = strokePx * 0.8f, center = thumb, style = Stroke(3.dp.toPx()))
        }

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                topLabel.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                letterSpacing = 2.sp,
                color = arcColor,
            )
            Row(verticalAlignment = Alignment.Top) {
                AnimatedContent(
                    targetState = value,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
                        } else {
                            (slideInVertically { -it / 2 } + fadeIn()) togetherWith (slideOutVertically { it / 2 } + fadeOut())
                        }
                    },
                    label = "dialValue",
                ) { v ->
                    Text(
                        formatNumber(v),
                        style = NumberStyle,
                        fontSize = (dialSize.value * 0.25f).sp,
                        color = if (active) scheme.onSurface else scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(
                    unit.ifEmpty { "°" },
                    style = NumberStyle,
                    fontSize = (dialSize.value * 0.075f).sp,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = (dialSize.value * 0.05f).dp),
                )
            }
            if (current != null) {
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(
                        Icons.Rounded.DeviceThermostat,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.dial_room, formatNumber(current) + unit.ifEmpty { "°" }),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun ringRadius(size: IntSize, strokePx: Float, tickSpacePx: Float): Float =
    minOf(size.width, size.height) / 2f - strokePx / 2f - tickSpacePx
