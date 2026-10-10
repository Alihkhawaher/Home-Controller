# Writing a connector

A connector teaches Home Controller a new family of devices. It is a small Kotlin module that finds the devices on
the local network, reads their state and changes it. The app does everything else: device cards, controls, rooms,
automations and the background hub.

This guide builds a complete connector for a made-up device, the **Acme smart plug**, step by step. Read
[architecture.md](architecture.md) first if you want the bigger picture.

- [Before you start](#before-you-start)
- [1. Create the module](#1-create-the-module)
- [2. Describe the protocol](#2-describe-the-protocol)
- [3. Implement the Connector](#3-implement-the-connector)
- [4. Implement the DeviceSession](#4-implement-the-devicesession)
- [5. Talk to the network through a transport](#5-talk-to-the-network-through-a-transport)
- [6. Test it with a fake transport](#6-test-it-with-a-fake-transport)
- [7. Register it in the app](#7-register-it-in-the-app)
- [The rules](#the-rules)
- [Checklist for your pull request](#checklist-for-your-pull-request)

## Before you start

A connector is only possible when the device has a **local API**: something it answers on your Wi-Fi without its
vendor's cloud. Good signs:

- the protocol is documented by the vendor (Shelly, Philips Hue, LG webOS);
- an open-source project already controls it locally (a Home Assistant integration that is marked *local push* or
  *local polling*, a Python library, a Node-RED node);
- the vendor app keeps working when the internet is down.

If the device only talks to its cloud, it cannot have a connector. Open a
[connector request](https://github.com/Alihkhawaher/Home-Controller/issues/new?template=connector_request.yml)
when you are unsure; someone may know.

## 1. Create the module

Each connector is a Gradle module under `connectors/`. Pick a short, lowercase id; it becomes part of every saved
device's key, so it never changes. This guide uses `acme`.

`connectors/acme/build.gradle.kts`:

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
```

A connector is a plain JVM library, not an Android module. That keeps it fast to test and free of Android APIs. If
you need JSON, use `org.json` as `compileOnly` (Android ships it) and add it to `testImplementation`, as the Gree
connector does.

Add the module to `settings.gradle.kts`, next to the Gree connector:

```kotlin
include(":connectors:gree")
include(":connectors:acme")
```

Sources go in `connectors/acme/src/main/kotlin/com/technlov/home/connectors/acme/`, tests in
`connectors/acme/src/test/kotlin/com/technlov/home/connectors/acme/`.

## 2. Describe the protocol

Our plug listens on **UDP port 9999** and speaks plain text. Every request gets the plug's status back:

```text
request:  status
request:  set on=1
request:  set on=0
reply:    id=a1b2c3;name=Desk lamp;on=1;watts=12.5;fw=1.4
```

Discovery sends `status` to the broadcast address; every plug on the Wi-Fi answers. Keep the parsing in one small,
pure object, so it is easy to test:

```kotlin
package com.technlov.home.connectors.acme

import com.technlov.home.core.DeviceState
import com.technlov.home.core.Keys
import com.technlov.home.core.Value

/** The plug's text protocol: "id=a1b2c3;name=Desk lamp;on=1;watts=12.5;fw=1.4". */
internal object AcmeProtocol {
    /** A connector-specific capability key: lowercase, snake_case, never renamed. */
    const val WATTS = "power_draw"

    /** The reply's fields, or null when it is not a plug status. */
    fun parse(text: String): Map<String, String>? {
        val fields = text.trim().split(';').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }.toMap()
        return fields.takeIf { "id" in it && "on" in it }
    }

    fun state(fields: Map<String, String>): DeviceState = DeviceState(
        values = buildMap<String, Value> {
            fields["on"]?.let { put(Keys.POWER, Value.Bool(it == "1")) }
            fields["watts"]?.toDoubleOrNull()?.let { put(WATTS, Value.Num(it)) }
            fields["fw"]?.let { put(Keys.FIRMWARE, Value.Text(it)) }
        },
    )
}
```

## 3. Implement the Connector

The connector describes itself, finds devices and opens sessions.

```kotlin
package com.technlov.home.connectors.acme

import com.technlov.home.core.Connector
import com.technlov.home.core.ConnectorException
import com.technlov.home.core.ConnectorInfo
import com.technlov.home.core.DeviceCategory
import com.technlov.home.core.DeviceRef
import com.technlov.home.core.DeviceSession
import com.technlov.home.core.LocalNetwork

class AcmePlugConnector(
    private val transport: AcmeTransport = UdpAcmeTransport(),
) : Connector {
    override val info = ConnectorInfo(
        id = ID,
        name = "Acme smart plugs",
        description = "Acme Wi-Fi plugs, switched directly on your Wi-Fi.",
        categories = setOf(DeviceCategory.SWITCH),
        version = "0.1.0",
        protocol = "Local UDP 9999, plain text (no cloud)",
    )

    override suspend fun discover(network: LocalNetwork, timeoutMs: Long): List<DeviceRef> =
        transport.broadcast(network.broadcast, PORT, "status", timeoutMs)
            .mapNotNull { reply -> AcmeProtocol.parse(reply.text)?.let { reply.host to it } }
            .distinctBy { (_, fields) -> fields.getValue("id") }
            .map { (host, fields) ->
                DeviceRef(
                    connectorId = ID,
                    id = fields.getValue("id"),     // stable: survives a new IP address
                    address = host,                 // where it answered this time
                    name = fields["name"] ?: "Acme plug",
                    category = DeviceCategory.SWITCH,
                    firmware = fields["fw"],
                )
            }

    override suspend fun open(device: DeviceRef): DeviceSession {
        if (device.connectorId != ID) throw ConnectorException("${device.name} is not an Acme plug")
        return AcmePlugSession(device, transport)
    }

    companion object {
        const val ID = "acme"
        const val PORT = 9999
    }
}
```

Notes:

- `discover` returns what answered within `timeoutMs` and nothing else. It must not throw because one reply was
  garbage: skip it.
- `DeviceRef.id` must be stable: a MAC address or a serial, never the IP address.
- Some routers, mesh systems and access points do not forward broadcasts. If your devices also answer unicast, sweep
  `network.prefix` + `1..254` as well, as the Gree connector does.
- `DeviceRef.extras` may hold non-secret details needed to reconnect (a protocol version, for example). Never a key,
  token or password.

## 4. Implement the DeviceSession

A session declares the device's capabilities, reads its state and applies changes.

```kotlin
package com.technlov.home.connectors.acme

import com.technlov.home.core.Capability
import com.technlov.home.core.ConnectorException
import com.technlov.home.core.DeviceRef
import com.technlov.home.core.DeviceSession
import com.technlov.home.core.DeviceState
import com.technlov.home.core.Keys
import com.technlov.home.core.SetValue
import com.technlov.home.core.Value

class AcmePlugSession(
    override val device: DeviceRef,
    private val transport: AcmeTransport,
) : DeviceSession {
    override val capabilities: List<Capability> = listOf(
        Capability.Power(),
        Capability.Reading(AcmeProtocol.WATTS, "Power draw", unit = "W"),
        Capability.Reading(Keys.FIRMWARE, "Firmware", diagnostic = true),
    )

    private var last: DeviceState? = null

    /** Reads only: "status" never changes the plug. A silent plug reads as offline with its last known values. */
    override suspend fun read(): DeviceState {
        val fields = ask("status")
            ?: return (last ?: DeviceState(emptyMap())).copy(online = false, error = "No answer from the plug")
        return AcmeProtocol.state(fields).also { last = it }
    }

    override suspend fun apply(changes: List<SetValue>): DeviceState {
        // Check every change before sending anything, so a bad value never leaves the plug half-changed.
        val requests = changes.map { change ->
            when (change.key) {
                Keys.POWER -> {
                    val on = (change.value as? Value.Bool)?.v
                        ?: throw ConnectorException("${device.name}: power needs on or off")
                    if (on) "set on=1" else "set on=0"
                }
                else -> throw ConnectorException("${device.name}: '${change.key}' cannot be changed")
            }
        }
        if (requests.isEmpty()) return read()
        var state: DeviceState? = null
        for (request in requests) {
            val fields = ask(request) ?: throw ConnectorException("${device.name} did not confirm the change")
            state = AcmeProtocol.state(fields)
        }
        return state!!.also { last = it }
    }

    private suspend fun ask(request: String): Map<String, String>? =
        transport.request(device.address, AcmePlugConnector.PORT, request, TIMEOUT_MS)?.let(AcmeProtocol::parse)

    private companion object {
        const val TIMEOUT_MS = 2_000L
    }
}
```

Choosing capabilities:

- Use the well-known keys in `Keys` (`power`, `mode`, `target_temperature`, `current_temperature`, `fan_speed`,
  `volume`, `brightness`, `input`...) whenever they fit. The UI recognises them and draws richer controls.
- Pick the closest kind: `Power` for the main switch, `Choice` for a fixed list, `Number` for a range with a step and
  unit, `Toggle` for secondary on/off features, `Reading` for read-only values. Mark technical readings
  `diagnostic = true`.
- Option values of a `Choice` and connector-specific keys are stored inside users' automations. Treat them as a
  public API: lowercase, stable, never renamed.
- Declare only what the device really supports. If models differ, ask the device on the first read and declare what
  it answered (the Gree session does this).

### Optional: pushes

If the device reports changes on its own over a connection you keep open, override `updates()` and return a hot
flow of whole states (a `MutableSharedFlow` the session emits into). The hub then shows each push at once and checks
the state-triggered automations straight away; it keeps polling `read()` as the fallback. The rules:

- the session owns the connection and the flow: `close()` stops both, and collecting the flow opens nothing;
- emit only what the device sent, never an "offline" state (polling finds an unreachable device);
- never let a slow collector block the code that reads the socket (`tryEmit` with a buffer that drops the oldest);
- give states from `read`, `apply` and `updates` non-decreasing `updatedAtMillis`, so a push that crossed a poll is
  not mistaken for a newer state.

The plug in this guide is polled, so it keeps the default (`null`). The Tuya session is a complete example of a
pushing one.

## 5. Talk to the network through a transport

Put the socket code behind a small interface. The connector uses the real one; tests use a fake device.

```kotlin
package com.technlov.home.connectors.acme

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** One reply to a broadcast, and the host (IP text) that sent it. */
class AcmeReply(val host: String, val text: String)

interface AcmeTransport {
    /** Send [text] to host:port and wait up to [timeoutMs] for the reply. Null on silence. */
    suspend fun request(host: String, port: Int, text: String, timeoutMs: Long): String?

    /** Send [text] to a broadcast address and collect every reply that arrives within [timeoutMs]. */
    suspend fun broadcast(address: String, port: Int, text: String, timeoutMs: Long): List<AcmeReply>
}

/** The real transport: UDP sockets on Dispatchers.IO, never on the main thread. */
class UdpAcmeTransport : AcmeTransport {
    override suspend fun request(host: String, port: Int, text: String, timeoutMs: Long): String? =
        withContext(Dispatchers.IO) {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs.toInt()
                send(socket, host, port, text)
                // A production connector should also check that the reply came from [host].
                receive(socket)?.text
            }
        }

    override suspend fun broadcast(address: String, port: Int, text: String, timeoutMs: Long): List<AcmeReply> =
        withContext(Dispatchers.IO) {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                send(socket, address, port, text)
                val deadline = System.currentTimeMillis() + timeoutMs
                val replies = mutableListOf<AcmeReply>()
                while (true) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) break
                    socket.soTimeout = left.toInt()
                    replies += receive(socket) ?: break
                }
                replies
            }
        }

    private fun send(socket: DatagramSocket, host: String, port: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(host), port))
    }

    private fun receive(socket: DatagramSocket): AcmeReply? {
        val packet = DatagramPacket(ByteArray(2048), 2048)
        return try {
            socket.receive(packet)
            AcmeReply(packet.address.hostAddress ?: "", String(packet.data, 0, packet.length, Charsets.UTF_8))
        } catch (e: SocketTimeoutException) {
            null
        }
    }
}
```

UDP loses packets on busy or weak Wi-Fi. For a real device, re-send a read when nothing came back, and re-send a
command only when nothing came back, so a command the device already answered is never repeated.

## 6. Test it with a fake transport

A fake device in memory proves the connector's behaviour without hardware, on any machine and in CI. Use addresses
from `192.0.2.0/24`, the range reserved for documentation, never a real one.

`connectors/acme/src/test/kotlin/com/technlov/home/connectors/acme/AcmePlugConnectorTest.kt`:

```kotlin
package com.technlov.home.connectors.acme

import com.technlov.home.core.Keys
import com.technlov.home.core.LocalNetwork
import com.technlov.home.core.SetValue
import com.technlov.home.core.Value
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** A plug in memory that records every request it receives. */
class FakePlug(var on: Boolean = false, var silent: Boolean = false) : AcmeTransport {
    val received = mutableListOf<String>()

    override suspend fun request(host: String, port: Int, text: String, timeoutMs: Long): String? {
        received += text
        if (silent) return null
        when (text) {
            "set on=1" -> on = true
            "set on=0" -> on = false
        }
        return "id=test01;name=Test plug;on=${if (on) 1 else 0};watts=${if (on) 40.5 else 0.0};fw=1.4"
    }

    override suspend fun broadcast(address: String, port: Int, text: String, timeoutMs: Long): List<AcmeReply> =
        listOfNotNull(request(HOST, port, text, timeoutMs)?.let { AcmeReply(HOST, it) })

    companion object {
        const val HOST = "192.0.2.10"
    }
}

class AcmePlugConnectorTest {
    private val network = LocalNetwork(broadcast = "192.0.2.255", prefix = "192.0.2.")

    @Test
    fun `discover finds the plug`() = runTest {
        val found = AcmePlugConnector(FakePlug()).discover(network, 500).single()

        assertEquals("test01", found.id)
        assertEquals(FakePlug.HOST, found.address)
    }

    @Test
    fun `read never changes the plug`() = runTest {
        val plug = FakePlug(on = true)
        val connector = AcmePlugConnector(plug)
        val session = connector.open(connector.discover(network, 500).single())
        plug.received.clear()

        val state = session.read()

        assertEquals(true, state.bool(Keys.POWER))
        assertEquals(listOf("status"), plug.received)
    }

    @Test
    fun `apply switches the plug off`() = runTest {
        val plug = FakePlug(on = true)
        val connector = AcmePlugConnector(plug)
        val session = connector.open(connector.discover(network, 500).single())

        val state = session.apply(listOf(SetValue(Keys.POWER, Value.Bool(false))))

        assertEquals(false, state.bool(Keys.POWER))
        assertFalse(plug.on)
    }

    @Test
    fun `a bad value is refused before anything is sent`() = runTest {
        val plug = FakePlug()
        val connector = AcmePlugConnector(plug)
        val session = connector.open(connector.discover(network, 500).single())
        plug.received.clear()

        runCatching { session.apply(listOf(SetValue(Keys.POWER, Value.Text("maybe")))) }

        assertEquals(emptyList<String>(), plug.received)
    }

    @Test
    fun `a silent plug reads as offline`() = runTest {
        val plug = FakePlug()
        val connector = AcmePlugConnector(plug)
        val session = connector.open(connector.discover(network, 500).single())
        plug.silent = true

        assertFalse(session.read().online)
    }
}
```

Run the tests with:

```sh
./gradlew :connectors:acme:test
```

For a real protocol, also test the parsing against replies captured from a real device, with every address, MAC,
serial and key replaced by made-up values before you commit them. The Gree connector's tests use a `FakeGreeUnit`
that speaks the full encrypted protocol; it is a good model for anything more complex than this plug.

## 7. Register it in the app

Three lines connect the module to the app:

1. `settings.gradle.kts` includes the module (step 1).
2. `app/build.gradle.kts` depends on it:

   ```kotlin
   dependencies {
       implementation(project(":core"))
       implementation(project(":connectors:gree"))
       implementation(project(":connectors:acme"))
       // ...
   }
   ```

3. [`app/src/main/java/com/technlov/homecontroller/Connectors.kt`](../app/src/main/java/com/technlov/homecontroller/Connectors.kt)
   adds it to the `ConnectorRegistry` the app builds at start-up:

   ```kotlin
   object Connectors {
       fun registry(): ConnectorRegistry =
           ConnectorRegistry(listOf(GreeConnector(), AcmePlugConnector()) + buildTypeConnectors())
   }
   ```

   `buildTypeConnectors()` adds a simulated-devices connector to debug builds only, so the app can be tried on an
   emulator without hardware. It is also a compact, readable example of a whole connector in one file.

Build and install (`./gradlew installDebug`), search for devices, and your plugs appear with a power switch, a power
reading and a firmware line in diagnostics. They can be used in automations straight away.

## The rules

Every connector follows these rules. Reviews check them.

1. **Local network only.** No cloud account, no vendor server, no internet access. Everything must work with the
   internet unplugged. If a device needs its cloud, it does not get a connector.
2. **Never log or expose secrets.** Device keys, tokens and passwords never appear in logs, exception messages,
   `toString()`, `DeviceRef.extras` or the UI. Keep a key in memory for the session. If a device hands its key to
   anyone on the LAN (as Gree units do), fetch it again on `open` rather than storing it.
3. **`read()` never changes a device.** Not a setting, not a mode, not a clock. Only `apply()` changes a device, and
   only with the changes it was given.
4. **Validate before sending.** `apply()` checks every change first and throws `ConnectorException` for an unknown
   key or an out-of-range value before anything goes out.
5. **Never block the main thread.** All network I/O runs in `suspend` functions on `Dispatchers.IO`.
6. **Stable identity.** `DeviceRef.id` survives IP changes. On `open`, check that the device answering at the saved
   address is the one you expect.
7. **Clear errors.** Throw `ConnectorException` with a message a user can act on ("did not confirm the change",
   "a different unit answers at this address; search again"). A device that stops answering during `read()` returns
   `online = false` with the reason in `error`.
8. **No new heavy dependencies.** Prefer the JDK and what Android already ships. Ask in an issue before adding a
   library.

## Checklist for your pull request

- [ ] The module builds and `./gradlew test` passes.
- [ ] Tests cover discovery, a read, a change, a refused value and a silent device, with a fake transport.
- [ ] No real IP address, MAC, serial, Wi-Fi name or key anywhere in the code, tests or docs.
- [ ] `docs/connectors/<id>.md` describes the protocol, the value mappings and known quirks.
- [ ] The README's supported devices table and `CHANGELOG.md` have a line for the connector.
- [ ] Credit any project whose protocol research you relied on, in the connector's doc and the README.
