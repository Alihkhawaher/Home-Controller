package com.technlov.home.connectors.tuya

/**
 * Tuya LAN command codes, from tinytuya's `command_types.py` (the Tuya `lan_protocol.h`). Only the ones this
 * connector uses are named; others are sent/received as their raw number.
 */
internal object TuyaCmd {
    const val SESS_KEY_NEG_START = 0x03
    const val SESS_KEY_NEG_RESP = 0x04
    const val SESS_KEY_NEG_FINISH = 0x05
    const val CONTROL = 0x07
    const val STATUS = 0x08
    const val HEART_BEAT = 0x09
    const val DP_QUERY = 0x0a
    const val CONTROL_NEW = 0x0d
    const val DP_QUERY_NEW = 0x10
    const val UPDATEDPS = 0x12
    const val REQ_DEVINFO = 0x25

    /** Commands whose payload never carries the `3.x` + 12-null version header (from tinytuya's NO_PROTOCOL_HEADER_CMDS). */
    val NO_PROTOCOL_HEADER = setOf(DP_QUERY, DP_QUERY_NEW, UPDATEDPS, HEART_BEAT, SESS_KEY_NEG_START, SESS_KEY_NEG_RESP, SESS_KEY_NEG_FINISH)
}

/**
 * One decoded Tuya frame. [payload] is the frame's payload as tinytuya's `unpack_message` leaves it:
 *  - for 55AA frames it is still the AES-ECB cipher text (the caller decrypts it with the right key and version);
 *  - for 6699 frames it is already AES-GCM-decrypted plaintext, with the 4-byte return code stripped.
 * [crcGood] is the CRC32/HMAC check for 55AA, and GCM-tag success for 6699.
 */
internal data class TuyaFrame(
    val seqno: Long,
    val cmd: Int,
    val retcode: Long,
    val payload: ByteArray,
    val prefix: Long,
    val crcGood: Boolean,
)

/**
 * Builds and reads the Tuya LAN frame, ported byte-for-byte from tinytuya's `message_helper.py` and `header.py`.
 *
 * 55AA frame (protocol 3.1-3.4): `0x000055AA | seq(4) | cmd(4) | len(4) | [retcode(4) in replies] | payload |
 * CRC32(4) or HMAC-SHA256(32) | 0x0000AA55`, all big-endian; `len` counts everything after it.
 *
 * 6699 frame (protocol 3.5): `0x00006699 | reserved(2) | seq(4) | cmd(4) | len(4) | IV(12) | GCM(payload) |
 * tag(16) | 0x00009966`; the 14 header bytes after the prefix are the GCM associated data, and `len` counts
 * `IV + cipher text + tag` (replies prepend a 4-byte return code to the plaintext).
 */
internal object TuyaCodec {
    const val PREFIX_55AA = 0x000055AAL
    const val SUFFIX_55AA = 0x0000AA55L
    const val PREFIX_6699 = 0x00006699L
    const val SUFFIX_6699 = 0x00009966L

    const val HEADER_55AA = 16
    const val HEADER_6699 = 18
    private const val END_55AA = 8      // crc(4) + suffix(4)
    private const val END_55AA_HMAC = 36 // hmac(32) + suffix(4)
    private const val END_6699 = 20     // tag(16) + suffix(4)
    private const val RETCODE = 4
    private const val IV_LEN = 12
    private const val TAG_LEN = 16

    /** The total length of the frame that starts at [data] (header included), or null when there is not enough yet. */
    fun frameLength(data: ByteArray): Int? {
        if (data.size < 4) return null
        return when (readU32(data, 0)) {
            PREFIX_55AA -> if (data.size < HEADER_55AA) null else HEADER_55AA + readU32(data, 12).toInt()
            PREFIX_6699 -> if (data.size < HEADER_6699) null else HEADER_6699 + readU32(data, 14).toInt() + 4
            else -> throw TuyaProtocolException("frame prefix is not 55AA or 6699")
        }
    }

    /**
     * A 55AA frame. [hmacKey] non-null switches integrity from CRC32 to HMAC-SHA256 (protocol 3.4). [retcode] is
     * null for a request (the client never sends one) and 0 for a reply (devices prepend one after the header).
     */
    fun encode55AA(seqno: Long, cmd: Int, payload: ByteArray, hmacKey: ByteArray?, retcode: Long? = null): ByteArray {
        val endLen = if (hmacKey != null) END_55AA_HMAC else END_55AA
        val rc = if (retcode != null) RETCODE else 0
        val header = ByteArray(HEADER_55AA)
        writeU32(header, 0, PREFIX_55AA)
        writeU32(header, 4, seqno)
        writeU32(header, 8, cmd.toLong())
        writeU32(header, 12, (payload.size + rc + endLen).toLong())
        val body = if (retcode != null) header + u32(retcode) + payload else header + payload
        val mac = if (hmacKey != null) TuyaCrypto.hmacSha256(hmacKey, body) else u32(TuyaCrypto.crc32(body))
        return body + mac + u32(SUFFIX_55AA)
    }

    /**
     * A 6699 frame (protocol 3.5). The payload is GCM-encrypted with [key]; [iv] must be 12 bytes. [retcode] is null
     * for a request and 0 for a reply (prepended to the plaintext before encryption).
     */
    fun encode6699(seqno: Long, cmd: Int, payload: ByteArray, key: ByteArray, iv: ByteArray, retcode: Long? = null): ByteArray {
        val plain = if (retcode != null) u32(retcode) + payload else payload
        val msgLen = IV_LEN + plain.size + TAG_LEN
        val header = ByteArray(HEADER_6699)
        writeU32(header, 0, PREFIX_6699)
        // bytes 4..6 are a reserved 16-bit field, left 0
        writeU32(header, 6, seqno)
        writeU32(header, 10, cmd.toLong())
        writeU32(header, 14, msgLen.toLong())
        val aad = header.copyOfRange(4, HEADER_6699)
        val (ct, tag) = TuyaCrypto.gcmEncrypt(plain, key, iv, aad)
        return header + iv + ct + tag + u32(SUFFIX_6699)
    }

    /**
     * Reads one whole frame. [frameKey] verifies the HMAC of a 55AA frame (protocol 3.4) and decrypts a 6699 frame
     * (the session key, or the UDP key for discovery); pass null for a plain CRC32 55AA frame (3.3 and most
     * discovery). [hasRetcode] is true for a device reply (the usual case) and false when parsing a client request.
     * Throws [TuyaProtocolException] on a malformed frame.
     */
    fun parse(data: ByteArray, frameKey: ByteArray?, hasRetcode: Boolean = true): TuyaFrame =
        when (val prefix = if (data.size >= 4) readU32(data, 0) else -1L) {
            PREFIX_55AA -> parse55AA(data, frameKey, hasRetcode)
            PREFIX_6699 -> parse6699(data, frameKey ?: throw TuyaProtocolException("6699 frame needs a key"), hasRetcode)
            else -> throw TuyaProtocolException("frame prefix %08X is not 55AA or 6699".format(prefix))
        }

    private fun parse55AA(data: ByteArray, hmacKey: ByteArray?, hasRetcode: Boolean): TuyaFrame {
        if (data.size < HEADER_55AA) throw TuyaProtocolException("frame shorter than its header")
        val seqno = readU32(data, 4)
        val cmd = readU32(data, 8).toInt()
        val length = readU32(data, 12).toInt()
        val total = HEADER_55AA + length
        val endLen = if (hmacKey != null) END_55AA_HMAC else END_55AA
        val rc = if (hasRetcode) RETCODE else 0
        if (data.size < total || length < rc + endLen) throw TuyaProtocolException("frame length does not add up")
        val retcode = if (hasRetcode) readU32(data, HEADER_55AA) else 0L
        val payload = data.copyOfRange(HEADER_55AA + rc, total - endLen)
        val mac = data.copyOfRange(total - endLen, total - 4)
        val suffix = readU32(data, total - 4)
        val over = data.copyOfRange(0, total - endLen)
        val computed = if (hmacKey != null) TuyaCrypto.hmacSha256(hmacKey, over) else u32(TuyaCrypto.crc32(over))
        val crcGood = suffix == SUFFIX_55AA && mac.contentEquals(computed)
        return TuyaFrame(seqno, cmd, retcode, payload, PREFIX_55AA, crcGood)
    }

    private fun parse6699(data: ByteArray, key: ByteArray, hasRetcode: Boolean): TuyaFrame {
        if (data.size < HEADER_6699) throw TuyaProtocolException("frame shorter than its header")
        val seqno = readU32(data, 6)
        val cmd = readU32(data, 10).toInt()
        val length = readU32(data, 14).toInt()
        val total = HEADER_6699 + length + 4
        if (data.size < total || length < IV_LEN + TAG_LEN) throw TuyaProtocolException("frame length does not add up")
        val iv = data.copyOfRange(HEADER_6699, HEADER_6699 + IV_LEN)
        val ct = data.copyOfRange(HEADER_6699 + IV_LEN, total - END_6699)
        val tag = data.copyOfRange(total - END_6699, total - 4)
        val aad = data.copyOfRange(4, HEADER_6699)
        val plain = TuyaCrypto.gcmDecrypt(ct, tag, key, iv, aad)
        val strip = hasRetcode && plain.size >= RETCODE
        val retcode = if (strip) readU32(plain, 0) else 0L
        val payload = if (strip) plain.copyOfRange(RETCODE, plain.size) else plain
        return TuyaFrame(seqno, cmd, retcode, payload, PREFIX_6699, true)
    }

    private fun u32(v: Long): ByteArray = ByteArray(4).also { writeU32(it, 0, v) }

    private fun readU32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun writeU32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }
}
