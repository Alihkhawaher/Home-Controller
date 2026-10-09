package com.technlov.home.core

import java.time.LocalDateTime

/** Placeholder: the engine agent replaces this with the full implementation and its tests. */
class DefaultAutomationEngine : AutomationEngine {
    override fun due(
        now: LocalDateTime,
        automations: List<Automation>,
        states: Map<DeviceKey, DeviceState>,
        previousStates: Map<DeviceKey, DeviceState>,
        lastRun: Map<String, LocalDateTime>,
    ): List<Automation> = emptyList()
}
