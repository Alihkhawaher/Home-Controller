package com.technlov.home.connectors.tuya

import com.technlov.home.core.DeviceCategory
import com.technlov.home.core.DeviceRef
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * Reads a Home Controller Tuya import code -- the one-scan way to load a whole set of devices without typing any
 * 16-character key. A companion PC tool (which does have the Tuya cloud credentials) renders the code as a QR; the
 * app scans it. No key ever leaves the two devices in cleartext on screen for long, and none is typed.
 *
 * Format v1: `HCTUYA1:` + base64url( gzip( UTF-8 JSON ) ), no padding. The JSON is
 * `{"devices":[{"id","key","ver","name","cat","pid","dps":[[dp_id,code,type,scale,unit,writable], ...]}]}` where
 * `ver` may be missing (the app's UDP discovery fills IP and version by matching the device id). Each device becomes
 * a [DeviceRef] with its key and dp spec in [DeviceRef.extras]; [DeviceRef.address] is left empty for discovery to
 * fill. Keys live only in the returned refs (which the app stores in its encrypted storage) and are never logged.
 */
object TuyaImport {
    const val PREFIX = "HCTUYA1:"
    private const val FAMILY = "HCTUYA"

    /**
     * Parses [qr] into the devices it carries. Throws [IllegalArgumentException] with a clear message for an unknown
     * prefix/version or malformed data. Devices without a 16-character key are skipped; an import with none usable
     * throws.
     */
    fun parse(qr: String): List<DeviceRef> {
        val text = qr.trim()
        if (!text.startsWith(PREFIX)) {
            if (text.startsWith(FAMILY)) {
                throw IllegalArgumentException("this import code is a newer version than this app understands; update the app")
            }
            throw IllegalArgumentException("this is not a Home Controller Tuya import code")
        }
        val encoded = text.substring(PREFIX.length)
        val gz = try {
            Base64.getUrlDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("the import code is damaged (not valid base64url)")
        }
        val jsonBytes = try {
            GZIPInputStream(ByteArrayInputStream(gz)).use { it.readBytes() }
        } catch (e: Exception) {
            throw IllegalArgumentException("the import code is damaged (not valid gzip)")
        }
        val root = try {
            JSONObject(String(jsonBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw IllegalArgumentException("the import code is damaged (not valid JSON)")
        }
        val devices = root.optJSONArray("devices") ?: throw IllegalArgumentException("the import code has no devices")

        val out = ArrayList<DeviceRef>()
        for (i in 0 until devices.length()) {
            val d = devices.optJSONObject(i) ?: continue
            val id = d.optString("id").trim().takeIf { it.isNotEmpty() } ?: continue
            val key = d.optString("key").trim()
            if (key.length != 16) continue // a device with no valid key cannot be controlled; skip it
            out += toRef(id, key, d)
        }
        if (out.isEmpty()) throw IllegalArgumentException("the import code has no usable devices")
        return out
    }

    private fun toRef(id: String, key: String, d: JSONObject): DeviceRef {
        val version = version(d)
        val category = d.optString("cat").trim().takeIf { it.isNotEmpty() }
        val productId = d.optString("pid").trim().takeIf { it.isNotEmpty() }
        val name = d.optString("name").trim().takeIf { it.isNotEmpty() } ?: "Tuya device ${id.takeLast(4).uppercase()}"
        val dpSpec = (d.opt("dps") as? JSONArray)?.toString()

        val extras = buildMap {
            put(TuyaConnector.EXTRA_LOCAL_KEY, key)
            if (version != null) put(TuyaConnector.EXTRA_VERSION, version)
            if (category != null) put(TuyaConnector.EXTRA_CATEGORY, category)
            if (productId != null) put(TuyaConnector.EXTRA_PRODUCT_ID, productId)
            if (dpSpec != null) put(TuyaConnector.EXTRA_DP_SPEC, dpSpec)
        }
        return DeviceRef(
            connectorId = TuyaConnector.ID,
            id = id,
            address = "", // discovery fills the IP by matching the device id
            name = name,
            category = category(category, productId),
            model = productId,
            extras = extras,
        )
    }

    /** `ver` may be a number (3.4) or a string ("3.4"), or missing (discovery fills it). */
    private fun version(d: JSONObject): String? {
        if (!d.has("ver") || d.isNull("ver")) return null
        return when (val v = d.opt("ver")) {
            is Number -> {
                val dbl = v.toDouble()
                if (dbl == Math.floor(dbl)) dbl.toInt().toString() else dbl.toString()
            }
            else -> v.toString().trim().takeIf { it.isNotEmpty() }
        }
    }

    private fun category(cat: String?, productId: String?): DeviceCategory = when {
        cat.equals("cz", true) -> DeviceCategory.SWITCH
        cat.equals("zndb", true) || (productId != null && productId in TuyaModel.PRODUCT_SPECS) -> DeviceCategory.SENSOR
        else -> DeviceCategory.OTHER
    }
}
