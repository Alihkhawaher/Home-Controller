package com.technlov.home.core

/**
 * Adopting a device that is not on the home Wi-Fi yet ("pairing"). Many Wi-Fi devices, put into setup mode, open
 * their own hotspot; the phone joins it and hands over the home Wi-Fi name and password, then the device joins the
 * home network and the connector's normal [Connector.discover] finds it.
 *
 * A connector that supports this returns a [Provisioning] from [Connector.provisioning]. The app owns the Android
 * side (scanning for setup hotspots, joining one, routing traffic through it, asking for the home password) and
 * gives the connector a [ProvisioningChannel] bound to the device's hotspot.
 *
 * The home Wi-Fi password is typed by the user for this one hand-over: the app never stores it, and connectors must
 * never log it.
 */
interface Provisioning {
    /** "Add a new Gree air conditioner". */
    val title: String

    /** How to put the device into setup mode, one step per item (shown in order). */
    val setupSteps: List<String>

    /** Whether a Wi-Fi network name looks like this kind of device in setup mode. */
    fun isSetupHotspot(ssid: String): Boolean

    /** The setup hotspot's password, if the vendor uses a fixed one (Gree: "12345678"); null if it is open. */
    val setupHotspotPassword: String?

    /** True when only 2.4 GHz home networks work (most Wi-Fi modules). */
    val needs24GHz: Boolean get() = true

    /**
     * Send the home network's credentials through [channel] (already bound to the device's hotspot). Returns what
     * the device answered. Never log [password].
     */
    suspend fun sendWifiCredentials(channel: ProvisioningChannel, ssid: String, password: String): ProvisioningResult

    /**
     * After the hand-over: does this device (found by [Connector.discover] on the home network) belong to the
     * hotspot the user set up? Lets the app pick out the new device among the others.
     */
    fun matchesSetupHotspot(device: DeviceRef, setupSsid: String): Boolean
}

/** Datagrams over the device's setup hotspot. */
interface ProvisioningChannel {
    /** Send [payload] to [host]:[port]; return the first reply within [timeoutMs], or null. */
    suspend fun sendAndReceive(host: String, port: Int, payload: ByteArray, timeoutMs: Long): ByteArray?
}

data class ProvisioningResult(
    /** The device confirmed (or, for devices that never answer, the message was sent). */
    val accepted: Boolean,
    /** What to tell the user, e.g. "The unit accepted the Wi-Fi details and is joining HomeWiFi". */
    val message: String,
)

/** Optional: connectors that can adopt unconfigured devices override this. */
val Connector.provisioningSupport: Provisioning?
    get() = (this as? ProvisioningCapable)?.provisioning

interface ProvisioningCapable {
    val provisioning: Provisioning
}
