package com.technlov.homecontroller.ui.device

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.technlov.homecontroller.R
import com.technlov.homecontroller.history.HistoryRange
import com.technlov.homecontroller.history.PowerHistoryUi
import com.technlov.homecontroller.history.Sample
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** "Power history": a range picker, a few headline numbers, the power line and energy bars. */
@Composable
fun PowerHistoryCard(history: PowerHistoryUi, onRange: (HistoryRange) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryRange.entries.forEach { r ->
                    FilterChip(
                        selected = r == history.range,
                        onClick = { onRange(r) },
                        label = {
                            Text(stringResource(when (r) {
                                HistoryRange.DAY_1 -> R.string.history_range_day
                                HistoryRange.DAYS_7 -> R.string.history_range_week
                                HistoryRange.DAYS_30 -> R.string.history_range_month
                            }))
                        },
                    )
                }
            }
            if (history.empty) {
                Text(
                    stringResource(R.string.history_collecting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                return@Column
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat(stringResource(R.string.history_now), history.nowW?.let(::watts) ?: "–")
                Stat(stringResource(R.string.history_peak), history.peakW?.let(::watts) ?: "–")
                Stat(stringResource(R.string.history_today), history.todayKwh?.let(::kwh) ?: "–")
                Stat(stringResource(R.string.history_month), history.monthKwh?.let(::kwh) ?: "–")
            }
            Text(
                stringResource(R.string.history_power_axis, history.peakW?.let(::watts) ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            PowerLine(history.line, history.from, history.to, history.gapMs, scheme.primary)
            TimeAxis(history)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    if (history.range == HistoryRange.DAY_1) R.string.history_energy_hourly else R.string.history_energy_daily,
                    history.bars.maxOfOrNull { it.second }?.let(::kwh) ?: "",
                ),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            EnergyBars(history.bars, scheme.tertiary)
            Text(
                stringResource(if (history.fromCounter) R.string.history_note_counter else R.string.history_note_power),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The power line, broken where readings are further apart than [gapMs], with a soft fill underneath. */
@Composable
private fun PowerLine(points: List<Sample>, from: Long, to: Long, gapMs: Long, color: Color) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(140.dp),
    ) {
        val top = (points.maxOfOrNull { it.value } ?: 1.0).coerceAtLeast(1.0) * 1.1
        val span = (to - from).coerceAtLeast(1L).toFloat()
        fun x(t: Long) = ((t - from) / span).coerceIn(0f, 1f) * size.width
        fun y(v: Double) = size.height - (v / top).toFloat().coerceIn(0f, 1f) * size.height
        for (i in 0..3) {
            val gy = size.height * i / 3f
            drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
        }
        // split into runs without gaps
        val runs = mutableListOf<MutableList<Sample>>()
        points.forEach { p ->
            val run = runs.lastOrNull()
            if (run == null || p.atMillis - run.last().atMillis > gapMs) runs += mutableListOf(p) else run += p
        }
        runs.forEach { run ->
            val line = Path()
            val fill = Path()
            run.forEachIndexed { i, p ->
                val px = x(p.atMillis)
                val py = y(p.value)
                if (i == 0) {
                    line.moveTo(px, py)
                    fill.moveTo(px, size.height)
                    fill.lineTo(px, py)
                } else {
                    line.lineTo(px, py)
                    fill.lineTo(px, py)
                }
            }
            fill.lineTo(x(run.last().atMillis), size.height)
            fill.close()
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.02f))))
            if (run.size == 1) {
                drawCircle(color, radius = 3.dp.toPx(), center = Offset(x(run[0].atMillis), y(run[0].value)))
            } else {
                drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun EnergyBars(bars: List<Pair<Long, Double>>, color: Color) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(72.dp),
    ) {
        if (bars.isEmpty()) return@Canvas
        val top = (bars.maxOf { it.second }).coerceAtLeast(0.001)
        val slot = size.width / bars.size
        val w = (slot * 0.7f).coerceAtLeast(1f)
        bars.forEachIndexed { i, (_, kwh) ->
            val h = (kwh / top).toFloat() * size.height
            drawRoundRect(
                color = color,
                topLeft = Offset(i * slot + (slot - w) / 2, size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            )
        }
    }
}

@Composable
private fun TimeAxis(history: PowerHistoryUi) {
    val pattern = if (history.range == HistoryRange.DAY_1) "HH:mm" else "d MMM"
    val fmt = DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault())
    val mid = history.from + (history.to - history.from) / 2
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(history.from, mid, history.to).forEach {
            Text(
                fmt.format(Instant.ofEpochMilli(it)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun watts(v: Double): String {
    val nf = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
    return if (v >= 1_000) nf.format(v / 1_000) + " kW" else nf.format(v) + " W"
}

private fun kwh(v: Double): String {
    val nf = NumberFormat.getNumberInstance().apply { maximumFractionDigits = if (v < 10) 2 else 1 }
    return nf.format(v) + " kWh"
}
