package com.technlov.homecontroller.history

import com.technlov.homecontroller.history.HistoryMath.DAY
import com.technlov.homecontroller.history.HistoryMath.HOUR
import com.technlov.homecontroller.history.HistoryMath.MAX_GAP_MS
import com.technlov.homecontroller.history.HistoryMath.MINUTE
import java.time.Instant
import java.time.ZoneId

enum class HistoryRange(val millis: Long) { DAY_1(DAY), DAYS_7(7 * DAY), DAYS_30(30 * DAY) }

/** Everything the "Power history" card shows. */
data class PowerHistoryUi(
    val range: HistoryRange,
    val from: Long,
    val to: Long,
    /** Power (W) over the range: 5-minute averages for a day, hourly averages beyond. */
    val line: List<Sample>,
    /** Points further apart than this are drawn as a gap, not joined. */
    val gapMs: Long,
    /** Energy per hour (a day) or per day (7 and 30 days): bucket start -> kWh. */
    val bars: List<Pair<Long, Double>>,
    val nowW: Double?,
    val avgW: Double?,
    val peakW: Double?,
    val todayKwh: Double?,
    val monthKwh: Double?,
    /** Energy comes from the device's own counter (exact across gaps), not from integrating power. */
    val fromCounter: Boolean,
) {
    val empty: Boolean get() = line.isEmpty()
}

/**
 * Builds [PowerHistoryUi] from stored samples. Pure: the storage is passed in as two functions, so the tests feed
 * it plain lists. Day and month boundaries are the phone's local time.
 */
object HistoryQuery {
    fun build(
        range: HistoryRange,
        now: Long,
        zone: ZoneId,
        plan: HistoryPlan,
        minutes: (metric: String, from: Long, to: Long) -> List<Sample>,
        hours: (metric: String, from: Long, to: Long) -> List<HourStat>,
    ): PowerHistoryUi? {
        val power = plan.powerKey ?: return null
        val from = now - range.millis
        val thisHour = HistoryMath.hourOf(now)

        // The line: fine for a day, hourly beyond, always including the hour that is still filling.
        val recentPower = minutes(power, maxOf(from, thisHour - HOUR), now + 1)
        val line = when (range) {
            HistoryRange.DAY_1 -> bucket(minutes(power, from, now + 1), 5 * MINUTE)
            else -> hours(power, from, thisHour).map { Sample(it.hourMillis, it.avg) } +
                HistoryMath.hours(recentPower, integrate = false).filter { it.hourMillis >= thisHour }
                    .map { Sample(it.hourMillis, it.avg) }
        }

        // Energy: every hour since the start of the month (or of the range, if earlier), plus the current hour.
        val dayOf = { t: Long -> Instant.ofEpochMilli(t).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli() }
        val today = dayOf(now)
        val monthStart = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().withDayOfMonth(1).atStartOfDay(zone)
            .toInstant().toEpochMilli()
        val energyFrom = minOf(from, monthStart)
        val counter = plan.energyCounterKey
        val metric = counter ?: power
        // A counter needs the last reading BEFORE the window to measure the first hour's rise.
        val stored = hours(metric, energyFrom - (if (counter != null) 2 * DAY else 0L), thisHour)
        val current = HistoryMath.hours(minutes(metric, thisHour - MAX_GAP_MS, now + 1), integrate = counter == null)
            .filter { it.hourMillis == thisHour }
        val hourly = HistoryMath.hourlyKwh(stored + current, counter != null).filter { it.first >= energyFrom }
        val daily = linkedMapOf<Long, Double>()
        for ((h, kwh) in hourly) daily[dayOf(h)] = (daily[dayOf(h)] ?: 0.0) + kwh

        val bars = when (range) {
            HistoryRange.DAY_1 -> hourly.filter { it.first >= HistoryMath.hourOf(from) }
            else -> daily.filterKeys { it >= dayOf(from) }.toList()
        }
        val values = line.map { it.value }
        return PowerHistoryUi(
            range = range,
            from = from,
            to = now,
            line = line,
            gapMs = if (range == HistoryRange.DAY_1) 15 * MINUTE else 3 * HOUR,
            bars = bars,
            nowW = recentPower.lastOrNull()?.takeIf { now - it.atMillis <= MAX_GAP_MS }?.value,
            avgW = values.takeIf { it.isNotEmpty() }?.average(),
            peakW = values.maxOrNull(),
            todayKwh = daily[today],
            monthKwh = daily.filterKeys { it >= monthStart }.values.takeIf { it.isNotEmpty() }?.sum(),
            fromCounter = counter != null,
        )
    }

    /** Averages samples into fixed buckets (bucket start -> mean). */
    fun bucket(samples: List<Sample>, size: Long): List<Sample> =
        samples.groupBy { it.atMillis - Math.floorMod(it.atMillis, size) }
            .map { (start, s) -> Sample(start, s.map { it.value }.average()) }
            .sortedBy { it.atMillis }
}
