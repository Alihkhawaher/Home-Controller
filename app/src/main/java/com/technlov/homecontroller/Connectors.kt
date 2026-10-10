package com.technlov.homecontroller

import com.technlov.home.connectors.gree.GreeConnector
import com.technlov.home.connectors.tuya.TuyaConnector
import com.technlov.home.core.ConnectorRegistry
import com.technlov.home.core.EventLog

/**
 * Every connector (plugin) built into the app. Adding a device family is one line here, plus the module in
 * settings.gradle.kts and app/build.gradle.kts. [buildTypeConnectors] adds a simulator in debug builds only.
 * Each connector gets the app's diagnostics [log].
 */
object Connectors {
    fun registry(log: EventLog): ConnectorRegistry =
        ConnectorRegistry(listOf(GreeConnector(log = log), TuyaConnector(log = log)) + buildTypeConnectors())
}
