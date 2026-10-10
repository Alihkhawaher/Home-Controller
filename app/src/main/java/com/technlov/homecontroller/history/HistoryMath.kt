package com.technlov.homecontroller.history

import com.technlov.home.core.Capability
import com.technlov.home.core.DeviceState
import com.technlov.home.core.Value

/** One reading of one metric at one time. */
data class Sample(val atMillis: Long, val value: Double)

/** An hour of one metric: [kwh] is the energy in that hour (power metrics only, by integration). */
data class HourStat(
    val hourMillis: Long,
    val avg: Double,
    val min: Double,
    val max: Double,
    val count: Int,
    val kwh: Double? = null,
)

/** What the history keeps for a device, decided from its capabilities. */
data class HistoryPlan(
    /** Every electrical reading worth keeping: power, voltage, current, energy counters. */
    val metrics: Map<String, String>,
    /** The device's main power reading (W), the line in the chart. */
    val powerKey: String?,
    /** A cumulative energy counter (kWh) when the device keeps one, e.g. a meter's total -- exact across gaps. */
    val energyCounterKey: String?,
)

/**
 * The pure arithmetic of power history: no Android, no storage, so every rule here is unit-tested.
 *
 * Energy comes from one of two places. A device that keeps its own cumulative counter (a 3-phase meter's
 * `energy_total`) gives exact energy between any two readings, however far apart. A device that only reports
 * power (a plug) is integrated over time -- but only across short steps: a gap longer than [MAX_GAP_MS] (the phone
 * left home, the hub was stopped) is left out rather than guessed.
 */
object HistoryMath {
    const val MINUTE = 60_000L
    const val HOUR = 3_600_000L
    const val DAY = 86_400_000L
    /** Background polling is once a minute; three missed polls is a gap, not a slow minute. */
    const val MAX_GAP_MS = 5 * MINUTE

    private val ELECTRICAL_UNITS = setOf("w", "kw", "v", "a", "ma", "kwh", "wh")

    /** Picks what to record from a device's capabilities. */
    fun plan(capabilities: List<Capability>): HistoryPlan {
        val metrics = linkedMapOf<String, String>()
        for (c in capabilities) {
            val unit = when (c) {
                is Capability.Reading -> c.unit
                is Capability.Number -> if (!c.writable) c.unit else null
                else -> null
            } ?: continue
            if (unit.trim().lowercase() in ELECTRICAL_UNITS) metrics[c.key] = unit.trim()
        }
        val watts = metrics.filter { it.value.equals("W", ignoreCase = true) }.keys
        val powerKey = watts.firstOrNull { "total" in it.lowercase() } ?: watts.firstOrNull { "power" in it.lowercase() }
            ?: watts.firstOrNull()
        val counters = metrics.filter { it.value.equals("kWh", ignoreCase = true) }.keys
        val energyCounterKey = counters.firstOrNull { "total" in it.lowercase() && "produced" !in it.lowercase() }
        return HistoryPlan(metrics, powerKey, energyCounterKey)
    }

    /** The values of [plan]'s metrics in one reading (numbers only). */
    fun values(plan: HistoryPlan, state: DeviceState): Map<String, Double> =
        plan.metrics.keys.mapNotNull { k -> (state.values[k] as? Value.Num)?.v?.takeIf { it.isFinite() }?.let { k to it } }.toMap()

    fun minuteOf(t: Long): Long = t - Math.floorMod(t, MINUTE)
    fun hourOf(t: Long): Long = t - Math.floorMod(t, HOUR)

    /** Energy (kWh) under a power curve (W), trapezoids between neighbours; steps longer than [maxGapMs] skipped. */
    fun integrateKwh(samples: List<Sample>, maxGapMs: Long = MAX_GAP_MS): Double {
        var wattMs = 0.0
        for (i in 1 until samples.size) {
            val a = samples[i - 1]
            val b = samples[i]
            val dt = b.atMillis - a.atMillis
            if (dt <= 0 || dt > maxGapMs) continue
            wattMs += (a.value + b.value) / 2.0 * dt
        }
        return wattMs / 3_600_000.0 / 1_000.0
    }

    /** Energy from a cumulative counter: the rises between readings. A drop (counter reset) starts afresh. */
    fun counterKwh(samples: List<Sample>): Double {
        var total = 0.0
        for (i in 1 until samples.size) {
            val d = samples[i].value - samples[i - 1].value
            if (d > 0) total += d
        }
        return total
    }

    /**
     * Groups minute samples into hours. For the power metric [integrate] also fills each hour's kWh: every step
     * between neighbouring samples is counted once, in the hour it STARTS in -- so the step that crosses into the
     * next hour is not lost. To close an hour's last step, pass the first sample of the following hour too.
     */
    fun hours(samples: List<Sample>, integrate: Boolean, maxGapMs: Long = MAX_GAP_MS): List<HourStat> {
        val sorted = samples.sortedBy { it.atMillis }
        val energy = HashMap<Long, Double>()
        if (integrate) {
            for (i in 1 until sorted.size) {
                val a = sorted[i - 1]
                val b = sorted[i]
                val dt = b.atMillis - a.atMillis
                if (dt <= 0 || dt > maxGapMs) continue
                val h = hourOf(a.atMillis)
                energy[h] = (energy[h] ?: 0.0) + (a.value + b.value) / 2.0 * dt / 3_600_000.0 / 1_000.0
            }
        }
        return sorted.groupBy { hourOf(it.atMillis) }.map { (hour, inHour) ->
            val v = inHour.map { it.value }
            HourStat(
                hourMillis = hour,
                avg = v.average(),
                min = v.min(),
                max = v.max(),
                count = v.size,
                kwh = if (integrate) energy[hour] ?: 0.0 else null,
            )
        }.sortedBy { it.hourMillis }
    }

    /**
     * Energy per day (start of day -> kWh) from hourly stats. With a counter, each hour's rise is (its max) minus
     * (the previous known hour's max), so energy used during a gap is counted in the hour after it -- the total
     * stays exact. Without one, the hours' integrated kWh are summed.
     */
    fun dailyKwh(hours: List<HourStat>, counter: Boolean, dayOf: (Long) -> Long): Map<Long, Double> {
        val out = linkedMapOf<Long, Double>()
        for ((hour, kwh) in hourlyKwh(hours, counter)) {
            val day = dayOf(hour)
            out[day] = (out[day] ?: 0.0) + kwh
        }
        return out
    }

    /** Energy per hour (hour start -> kWh), by the same rules as [dailyKwh]. */
    fun hourlyKwh(hours: List<HourStat>, counter: Boolean): List<Pair<Long, Double>> {
        var previousMax: Double? = null
        return hours.sortedBy { it.hourMillis }.map { h ->
            val kwh = if (counter) {
                val p = previousMax
                previousMax = h.max
                if (p == null) 0.0 else (h.max - p).coerceAtLeast(0.0)
            } else {
                h.kwh ?: 0.0
            }
            h.hourMillis to kwh
        }
    }
}
