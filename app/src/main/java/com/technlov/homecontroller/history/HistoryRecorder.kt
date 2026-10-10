package com.technlov.homecontroller.history

import com.technlov.home.core.Capability
import com.technlov.home.core.DeviceKey
import com.technlov.home.core.DeviceState
import com.technlov.home.core.EventLog
import com.technlov.home.core.warn
import com.technlov.homecontroller.diag.LogSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Where the hub hands each successful reading. The hub's tests use [None]. */
interface HistorySink {
    fun record(device: DeviceKey, capabilities: List<Capability>, state: DeviceState, atMillis: Long)

    object None : HistorySink {
        override fun record(device: DeviceKey, capabilities: List<Capability>, state: DeviceState, atMillis: Long) = Unit
    }
}

/**
 * Keeps the electrical readings of every reading the hub makes (power, voltage, current, energy counters), one per
 * minute, and rolls them up into hours every [ROLL_UP_EVERY_MS]. Writes happen off the hub's polling path, so a slow
 * disk never delays a device. A device without electrical readings (an AC) records nothing.
 */
class HistoryRecorder(
    private val store: HistoryStore,
    private val scope: CoroutineScope,
    private val log: EventLog,
) : HistorySink {
    @Volatile private var lastRollUp = 0L

    override fun record(device: DeviceKey, capabilities: List<Capability>, state: DeviceState, atMillis: Long) {
        val plan = HistoryMath.plan(capabilities)
        if (plan.metrics.isEmpty()) return
        val values = HistoryMath.values(plan, state)
        if (values.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            try {
                store.record(device, plan.metrics, values, HistoryMath.minuteOf(atMillis))
                if (atMillis - lastRollUp >= ROLL_UP_EVERY_MS) {
                    lastRollUp = atMillis
                    store.rollUp(atMillis)
                }
            } catch (e: Exception) {
                log.warn(LogSource.HUB, "history: could not save a reading", e)
            }
        }
    }

    private companion object {
        const val ROLL_UP_EVERY_MS = 10 * HistoryMath.MINUTE
    }
}
