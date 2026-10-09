package com.technlov.homecontroller

import com.technlov.home.core.Connector

/** Release builds ship only the real connectors listed in [Connectors]. */
internal fun buildTypeConnectors(): List<Connector> = emptyList()
