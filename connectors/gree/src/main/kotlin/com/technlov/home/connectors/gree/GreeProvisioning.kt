package com.technlov.home.connectors.gree

import com.technlov.home.core.DeviceRef
import com.technlov.home.core.EventLog
import com.technlov.home.core.Provisioning
import com.technlov.home.core.ProvisioningChannel
import com.technlov.home.core.ProvisioningResult
import com.technlov.home.core.info
import com.technlov.home.core.trace
import com.technlov.home.core.warn
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Adopting a Gree unit that is not on the home Wi-Fi yet. In setup mode the unit opens its own hotspot, named with
 * the last 8 characters of its MAC address (password 12345678), at 192.168.1.1; one plain (unencrypted) JSON message
 * to UDP 7000 there, `{"psw":..,"ssid":..,"t":"wlan"}`, makes it join the home network. Sources: Gree's manuals
 * (hotspot, remote buttons) and the RobHofmann HomeAssistant-GreeClimateComponent README (the wlan message).
 *
 * The home Wi-Fi password goes into that one datagram and nowhere else: not into a log line, a result message or an
 * exception.
 */
class GreeProvisioning(
    private val log: EventLog = EventLog.None,
    private val timeoutMs: Long = 3_000,
    private val tries: Int = 3,
) : Provisioning {
    override val title = "Add a new Gree air conditioner"

    override val setupSteps = listOf(
        "Turn the air conditioner on with its remote.",
        "Hold the remote's WiFi button for 10 seconds, until the unit beeps. Remotes without a WiFi button: hold " +
            "MODE and TURBO together for 10 seconds.",
        "The unit now opens a Wi-Fi hotspot named with the last 8 characters of its MAC address (password " +
            "12345678) for about 8 minutes.",
        "While it is in setup mode, the remote does not control it.",
    )

    override val setupHotspotPassword: String = SETUP_PASSWORD

    override val needs24GHz: Boolean = true

    override fun isSetupHotspot(ssid: String): Boolean = SETUP_SSID.matches(unquote(ssid))

    override suspend fun sendWifiCredentials(channel: ProvisioningChannel, ssid: String, password: String): ProvisioningResult {
        val frame = GreeJson.write(linkedMapOf("psw" to password, "ssid" to ssid, "t" to "wlan"))
            .toByteArray(Charsets.UTF_8)
        val shown = GreeJson.write(linkedMapOf("psw" to "<hidden>", "ssid" to ssid, "t" to "wlan"))
        log.info(GreeClient.SOURCE, "pairing: sending the Wi-Fi details for '$ssid' to the unit's hotspot")
        for (attempt in 1..tries.coerceAtLeast(1)) {
            log.trace(GreeClient.SOURCE) { "-> $SETUP_HOST:$SETUP_PORT $shown (try $attempt/$tries)" }
            val reply = try {
                channel.sendAndReceive(SETUP_HOST, SETUP_PORT, frame, timeoutMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The channel's own message is not repeated: only its type, so nothing from the frame can leak.
                log.warn(GreeClient.SOURCE, "pairing: could not send to the unit's hotspot (${e.javaClass.simpleName})")
                return ProvisioningResult(
                    accepted = false,
                    message = "Could not reach the air conditioner over its hotspot (${e.javaClass.simpleName}). " +
                        "Check the phone is still connected to the unit's hotspot and try again.",
                )
            }
            if (reply == null) {
                log.trace(GreeClient.SOURCE) { "pairing: no reply (try $attempt/$tries)" }
                continue
            }
            return judge(reply, ssid, password)
        }
        log.warn(GreeClient.SOURCE, "pairing: no reply after $tries tries")
        return ProvisioningResult(
            accepted = false,
            message = "The air conditioner did not answer. Some units take the Wi-Fi details without answering: " +
                "reconnect the phone to '$ssid' and search for devices. If the unit does not appear, put it in " +
                "setup mode again and retry.",
        )
    }

    private fun judge(reply: ByteArray, ssid: String, password: String): ProvisioningResult {
        val text = String(reply, Charsets.UTF_8)
        val o = try {
            JSONObject(text.substring(0, text.lastIndexOf('}') + 1))
        } catch (e: Exception) {
            null
        }
        log.trace(GreeClient.SOURCE) {
            // A unit that echoes the details back must not put the password in the log.
            val shown = o?.let { JSONObject(it.toString()).apply { if (has("psw")) put("psw", "<hidden>") }.toString() }
            val safe = shown?.let { if (password.isNotEmpty()) it.replace(password, "<hidden>") else it }
            "<- $SETUP_HOST:$SETUP_PORT ${safe ?: "(not JSON, ${reply.size} bytes)"}"
        }
        val accepted = when {
            o == null -> false
            o.has("r") -> o.optInt("r", -1) == 200
            else -> o.optString("t") == "ret"
        }
        return if (accepted) {
            log.info(GreeClient.SOURCE, "pairing: the unit accepted the Wi-Fi details for '$ssid'")
            ProvisioningResult(
                accepted = true,
                message = "The air conditioner accepted the Wi-Fi details and is joining '$ssid'. Reconnect the " +
                    "phone to '$ssid' and search for devices in a minute.",
            )
        } else {
            val what = if (o == null) "an answer that is not JSON" else "r=${o.opt("r") ?: "?"}, t=${o.optString("t")}"
            log.warn(GreeClient.SOURCE, "pairing: the unit refused the Wi-Fi details ($what)")
            ProvisioningResult(
                accepted = false,
                message = "The air conditioner refused the Wi-Fi details ($what). Check the network name and " +
                    "password (2.4 GHz networks only) and try again.",
            )
        }
    }

    override fun matchesSetupHotspot(device: DeviceRef, setupSsid: String): Boolean {
        val s = unquote(setupSsid)
        if (device.connectorId != GreeConnector.ID || !SETUP_SSID.matches(s)) return false
        return GreeClient.normaliseMac(device.id).endsWith(s.lowercase())
    }

    override fun toString(): String = "GreeProvisioning"

    companion object {
        const val SETUP_HOST = "192.168.1.1"
        const val SETUP_PORT = 7000
        const val SETUP_PASSWORD = "12345678"
        private val SETUP_SSID = Regex("^[0-9A-Fa-f]{8}$")

        /** Android reports the current network name in double quotes ("\"1a2b3c4d\""); take them off. */
        private fun unquote(ssid: String): String {
            val s = ssid.trim()
            return if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) s.substring(1, s.length - 1) else s
        }
    }
}
