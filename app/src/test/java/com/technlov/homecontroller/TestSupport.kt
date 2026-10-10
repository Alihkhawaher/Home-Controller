package com.technlov.homecontroller

import androidx.datastore.core.DataStore
import com.technlov.home.core.Capability
import com.technlov.home.core.Connector
import com.technlov.home.core.ConnectorInfo
import com.technlov.home.core.DeviceCategory
import com.technlov.home.core.DeviceRef
import com.technlov.home.core.DeviceSession
import com.technlov.home.core.DeviceState
import com.technlov.home.core.Keys
import com.technlov.home.core.LocalNetwork
import com.technlov.home.core.SetValue
import com.technlov.home.core.Value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A DataStore in memory, for repository and hub tests. */
class InMemoryDataStore<T>(initial: T) : DataStore<T> {
    private val state = MutableStateFlow(initial)
    private val mutex = Mutex()
    override val data: Flow<T> = state
    override suspend fun updateData(transform: suspend (t: T) -> T): T = mutex.withLock {
        transform(state.value).also { state.value = it }
    }
    val value: T get() = state.value
}

/** Example devices use the documentation address range only. */
fun exampleRef(id: String = "ac-1", address: String = "192.0.2.10", connectorId: String = "fake") = DeviceRef(
    connectorId = connectorId,
    id = id,
    address = address,
    name = "Example AC $id",
    category = DeviceCategory.CLIMATE,
    model = "Model X",
    firmware = "1.0",
)

val climateCapabilities = listOf(
    Capability.Power(),
    Capability.Choice(Keys.MODE, "Mode", listOf(Capability.Option("cool", "Cool"), Capability.Option("heat", "Heat"))),
    Capability.Number(Keys.TARGET_TEMPERATURE, "Target temperature", 16.0, 30.0, 1.0, "°C"),
    Capability.Reading(Keys.CURRENT_TEMPERATURE, "Room temperature", "°C"),
    Capability.Toggle("turbo", "Turbo"),
    Capability.Reading(Keys.FAULT, "Fault", diagnostic = true),
)

/**
 * A scriptable connector: no network at all. Each device's state lives in [states]; [failReads] / [failApplies]
 * make it throw, and [applyGate] lets a test hold an apply in flight. With [pushing] on, sessions offer
 * [DeviceSession.updates] and [push] makes a device report a change on its own.
 */
class FakeConnector(override val info: ConnectorInfo = ConnectorInfo("fake", "Fake devices", "For tests", setOf(DeviceCategory.CLIMATE), "1", "None")) : Connector {
    val states = mutableMapOf<String, MutableMap<String, Value>>()
    var failReads = false
    var failApplies = false
    var applyGate: CompletableDeferred<Unit>? = null
    var opens = 0
    var pushing = false
    private val pushes = mutableMapOf<String, MutableSharedFlow<DeviceState>>()

    /** How many collectors follow a device's pushes right now (the hub's, while its session is open). */
    fun listeners(deviceId: String): Int = pushes[deviceId]?.subscriptionCount?.value ?: 0

    /** The device reports [changes] on its own. [at] stamps the state; the default is newer than any read so far. */
    fun push(deviceId: String, changes: Map<String, Value>, at: Long = System.currentTimeMillis()) {
        val values = states.getValue(deviceId)
        values.putAll(changes)
        pushes[deviceId]?.tryEmit(DeviceState(values.toMap(), updatedAtMillis = at))
    }

    override suspend fun discover(network: LocalNetwork, timeoutMs: Long): List<DeviceRef> = emptyList()

    override suspend fun open(device: DeviceRef): DeviceSession {
        opens++
        val values = states.getOrPut(device.id) {
            mutableMapOf(
                Keys.POWER to Value.Bool(false),
                Keys.MODE to Value.Text("cool"),
                Keys.TARGET_TEMPERATURE to Value.Num(24.0),
                Keys.CURRENT_TEMPERATURE to Value.Num(27.0),
            )
        }
        val updates = if (pushing) pushes.getOrPut(device.id) { MutableSharedFlow(extraBufferCapacity = 16) } else null
        return object : DeviceSession {
            override val device = device
            override val capabilities = climateCapabilities
            override fun updates(): Flow<DeviceState>? = updates
            override suspend fun read(): DeviceState {
                if (failReads) throw IllegalStateException("no reply")
                return DeviceState(values.toMap())
            }
            override suspend fun apply(changes: List<SetValue>): DeviceState {
                applyGate?.await()
                if (failApplies) throw IllegalStateException("refused")
                changes.forEach { values[it.key] = it.value }
                return DeviceState(values.toMap())
            }
        }
    }
}
