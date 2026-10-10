package com.technlov.home.core

import kotlinx.coroutines.flow.Flow

/**
 * The plugin contract of Home Controller.
 *
 * A [Connector] knows one family of devices (Gree air conditioners, a TV brand, smart switches...). It finds them on
 * the local network ([discover]) and opens a [DeviceSession] to one of them. A session declares what the device can
 * do as a list of [Capability]s; the app draws its controls from that list, so a new device family needs a new
 * connector and nothing else -- the same idea as Home Assistant's integrations and entities, but running inside the
 * phone app instead of on a server.
 *
 * Rules for every connector:
 *  - local network only: no cloud account, no server; everything works with the internet down;
 *  - suspend functions, never block the main thread (do I/O in Dispatchers.IO);
 *  - never log or expose a device key, token or password; keep them in [DeviceRef.extras] only if the device hands
 *    them out on the LAN and they are needed to reconnect;
 *  - [DeviceSession.read] must not change the device; only [DeviceSession.apply] does.
 */
interface Connector {
    val info: ConnectorInfo

    /** Look for this connector's devices on the local network. Returns what answered within [timeoutMs]. */
    suspend fun discover(network: LocalNetwork, timeoutMs: Long = 3_000): List<DeviceRef>

    /** Open a session to one device found earlier (or remembered by the app). */
    suspend fun open(device: DeviceRef): DeviceSession
}

/** What the app shows about a connector on its plugins screen. */
data class ConnectorInfo(
    val id: String,                       // stable, lowercase, e.g. "gree"
    val name: String,                     // "Gree air conditioners"
    val description: String,              // one sentence
    val categories: Set<DeviceCategory>,
    val version: String,
    val protocol: String,                 // "Local UDP 7000, AES (no cloud)"
    val homepage: String? = null,
)

enum class DeviceCategory { CLIMATE, TV, SWITCH, LIGHT, SENSOR, MEDIA, OTHER }

/**
 * A device as the app remembers it. [id] must be stable across IP changes (a MAC address, a serial); [address] is
 * where it was last seen. [extras] holds connector-specific, non-secret details needed to reconnect.
 */
data class DeviceRef(
    val connectorId: String,
    val id: String,
    val address: String,
    val name: String,
    val category: DeviceCategory,
    val model: String? = null,
    val firmware: String? = null,
    val extras: Map<String, String> = emptyMap(),
)

/** Where to look. [prefix] like "192.168.1." for a sweep; [broadcast] like "192.168.1.255". */
data class LocalNetwork(
    val broadcast: String,
    val prefix: String,
    val knownHosts: List<String> = emptyList(),
)

/** An open connection to one device. */
interface DeviceSession : AutoCloseable {
    val device: DeviceRef
    val capabilities: List<Capability>

    /** Current state. Reads only. */
    suspend fun read(): DeviceState

    /** Change one or more values in one go; returns the state afterwards. */
    suspend fun apply(changes: List<SetValue>): DeviceState

    /**
     * Optional push channel: the states a device reports on its own over a connection the session keeps open (a
     * switch flipped by hand, an alarm going off, a fresh meter reading), each as a whole state. Null -- the default
     * -- for a device that can only be polled.
     *
     * The flow is hot and belongs to the session: collecting it opens nothing, and it goes quiet after [close]. It
     * carries only what the device sent, never an "offline" state: the hub keeps polling [read] as the fallback and
     * finds an unreachable device that way. States from one session (from [read], [apply] and here) must carry
     * non-decreasing [DeviceState.updatedAtMillis], so a push that crossed a poll can be told from a newer one.
     */
    fun updates(): Flow<DeviceState>? = null

    override fun close() {}
}

data class SetValue(val key: String, val value: Value)

data class DeviceState(
    val values: Map<String, Value>,
    val online: Boolean = true,
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val error: String? = null,
) {
    fun bool(key: String): Boolean? = (values[key] as? Value.Bool)?.v
    fun number(key: String): Double? = (values[key] as? Value.Num)?.v
    fun text(key: String): String? = (values[key] as? Value.Text)?.v
}

sealed interface Value {
    data class Bool(val v: Boolean) : Value
    data class Num(val v: Double) : Value
    data class Text(val v: String) : Value
}

/**
 * What a device can do. The UI renders each kind generically; well-known [Keys] let it draw richer controls (a
 * climate dial for [Keys.TARGET_TEMPERATURE] + [Keys.CURRENT_TEMPERATURE], mode chips for [Keys.MODE]...).
 */
sealed interface Capability {
    val key: String
    val label: String

    /** On/off for the whole device. */
    data class Power(override val key: String = Keys.POWER, override val label: String = "Power") : Capability

    /** One of a fixed list (mode, fan speed, swing position, input source...). */
    data class Choice(
        override val key: String,
        override val label: String,
        val options: List<Option>,
        val writable: Boolean = true,
    ) : Capability

    /** A number in a range (target temperature, volume, brightness...). */
    data class Number(
        override val key: String,
        override val label: String,
        val min: Double,
        val max: Double,
        val step: Double = 1.0,
        val unit: String = "",
        val writable: Boolean = true,
    ) : Capability

    /** A secondary on/off (display light, turbo, quiet, sleep...). */
    data class Toggle(
        override val key: String,
        override val label: String,
        val writable: Boolean = true,
    ) : Capability

    /** A read-only value. [diagnostic] ones go to the device's diagnostics section. */
    data class Reading(
        override val key: String,
        override val label: String,
        val unit: String = "",
        val diagnostic: Boolean = false,
    ) : Capability

    data class Option(val value: String, val label: String)
}

/** Well-known capability keys shared by all connectors, so the UI can recognise them. */
object Keys {
    const val POWER = "power"
    const val MODE = "mode"
    const val TARGET_TEMPERATURE = "target_temperature"
    const val CURRENT_TEMPERATURE = "current_temperature"
    const val FAN_SPEED = "fan_speed"
    const val SWING_VERTICAL = "swing_vertical"
    const val SWING_HORIZONTAL = "swing_horizontal"
    const val DISPLAY_LIGHT = "display_light"
    const val FAULT = "fault"
    const val FIRMWARE = "firmware"
    const val VOLUME = "volume"
    const val BRIGHTNESS = "brightness"
    const val INPUT = "input"
}

/** All connectors built into the app. A new connector module adds one line to [ConnectorRegistry.all]'s source. */
class ConnectorRegistry(connectors: List<Connector>) {
    private val byId = connectors.associateBy { it.info.id }
    val all: List<Connector> = connectors.sortedBy { it.info.name }
    operator fun get(id: String): Connector? = byId[id]
}

class ConnectorException(message: String, cause: Throwable? = null) : Exception(message, cause)
