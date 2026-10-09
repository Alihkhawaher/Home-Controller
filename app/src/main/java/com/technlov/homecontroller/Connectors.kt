package com.technlov.homecontroller

import com.technlov.home.connectors.gree.GreeConnector
import com.technlov.home.core.ConnectorRegistry

/**
 * Every connector (plugin) built into the app. Adding a device family is one line here, plus the module in
 * settings.gradle.kts and app/build.gradle.kts. [buildTypeConnectors] adds a simulator in debug builds only.
 */
object Connectors {
    fun registry(): ConnectorRegistry = ConnectorRegistry(listOf(GreeConnector()) + buildTypeConnectors())
}
