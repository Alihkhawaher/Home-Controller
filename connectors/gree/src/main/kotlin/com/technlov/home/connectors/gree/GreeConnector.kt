package com.technlov.home.connectors.gree

import com.technlov.home.core.Connector
import com.technlov.home.core.ConnectorException
import com.technlov.home.core.ConnectorInfo
import com.technlov.home.core.DeviceCategory
import com.technlov.home.core.DeviceRef
import com.technlov.home.core.DeviceSession
import com.technlov.home.core.LocalNetwork

/**
 * Gree Wi-Fi air conditioners over their local protocol (JSON over UDP 7000, AES). Placeholder: the gree agent
 * replaces discover/open with the real implementation; the class name and [info] stay.
 */
class GreeConnector : Connector {
    override val info = ConnectorInfo(
        id = "gree",
        name = "Gree air conditioners",
        description = "Gree, EWPE and Gree-made Wi-Fi air conditioners, controlled directly on your Wi-Fi.",
        categories = setOf(DeviceCategory.CLIMATE),
        version = "0.1.0",
        protocol = "Local UDP 7000, AES-128 (no cloud, no account)",
    )

    override suspend fun discover(network: LocalNetwork, timeoutMs: Long): List<DeviceRef> =
        throw ConnectorException("not implemented yet")

    override suspend fun open(device: DeviceRef): DeviceSession = throw ConnectorException("not implemented yet")
}
