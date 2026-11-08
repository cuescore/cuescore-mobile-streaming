package app.brix.bonding

object Srtla {
    const val CONTROL_PACKET_TYPE_BIT = 0x8000
    const val CONTROL_TYPE_SIZE = 2
    const val GROUP_ID_SIZE = 256

    enum class PacketType(val rawValue: Int) {
        KEEPALIVE(0x1000),
        ACK(0x1100),
        REG1(0x1200),
        REG2(0x1201),
        REG3(0x1202),
        REG_ERR(0x1210),
        REG_NGP(0x1211),
        REG_NAK(0x1212),
    }

    fun createPacket(type: PacketType, length: Int): ByteArray {
        val packet = ByteArray(length)
        writeUInt16(packet, 0, type.rawValue or CONTROL_PACKET_TYPE_BIT)
        return packet
    }

    fun randomGroupId(): ByteArray {
        val bytes = ByteArray(GROUP_ID_SIZE)
        SecureRandomSource.fill(bytes)
        return bytes
    }
}

object Srt {
    const val DATA_PACKET_HEADER_SIZE = 16
    const val CONTROL_PACKET_TYPE_BIT = 0x8000

    enum class PacketType(val rawValue: Int) {
        HANDSHAKE(0x0000),
        KEEPALIVE(0x0001),
        ACK(0x0002),
        NAK(0x0003),
        SHUTDOWN(0x0005),
        ACKACK(0x0006),
    }

    fun isDataPacket(packet: ByteArray): Boolean = (packet[0].toInt() and 0x80) == 0

    fun getControlPacketType(packet: ByteArray): Int =
        readUInt16(packet, 0) and 0x7FFF

    fun getSequenceNumber(packet: ByteArray, offset: Int = 0): Long =
        readUInt32(packet, offset)

    /**
     * Whether packet `sn` has been acknowledged by an ACK with number `ackSn`.
     *
     * The number in an SRT ACK is the NEXT EXPECTED packet: everything strictly
     * below it is acknowledged, while the number itself has not yet arrived.
     * Previously this said `sn == ackSn -> true`, which removed from the buffer
     * exactly the packet the receiver was still missing.
     *
     * Field evidence from 31.08, independent of reading the spec: the server
     * sent NAKs for a number that was ALWAYS exactly one less than the first
     * packet in our buffer — `sn=8421 window=[8422..8661]`,
     * `sn=11719 window=[11720..11955]`, and every time. We couldn't find that
     * number in our buffer (80-90% of all NAKs went nowhere), no retransmission
     * was sent, and the loss became permanent: the receiving picture broke up
     * until the next keyframe, up to two seconds later.
     *
     * Comparison is performed on a ring: sequence numbers are 31-bit and wrap.
     */
    fun isSnAcked(sn: Long, ackSn: Long): Boolean =
        if (sn == ackSn) false
        else if (sn < ackSn) ackSn - sn < 100_000_000
        else sn - ackSn > 100_000_000

    fun isSnRange(sn: Long): Boolean = (sn and 0x8000_0000L) == 0x8000_0000L

    const val MAX_NAK_RANGE_SIZE = 10_000

    fun processNak(packet: ByteArray, onNak: (Long) -> Unit) {
        var offset = 16
        var totalIterations = 0
        while (offset <= packet.size - 4) {
            val nakSn = readUInt32(packet, offset)
            offset += 4
            if (isSnRange(nakSn)) {
                if (offset > packet.size - 4) return
                val upToNakSn = readUInt32(packet, offset)
                offset += 4
                val start = nakSn and 0x7FFF_FFFF
                if (start <= upToNakSn) {
                    val count = (upToNakSn - start + 1).coerceAtMost(MAX_NAK_RANGE_SIZE.toLong())
                    var sn = start
                    var i = 0L
                    while (i < count) {
                        onNak(sn)
                        sn += 1
                        i += 1
                        totalIterations += 1
                        if (totalIterations > MAX_NAK_RANGE_SIZE) return
                    }
                } else {
                    // Wrap-around: start > end → iterate start..MAX, then 0..end
                    var sn = start
                    var remaining = MAX_NAK_RANGE_SIZE - totalIterations
                    while (sn <= 0x7FFF_FFFFL && remaining > 0) {
                        onNak(sn)
                        sn += 1
                        remaining -= 1
                        totalIterations += 1
                    }
                    sn = 0
                    while (sn <= upToNakSn && remaining > 0) {
                        onNak(sn)
                        sn += 1
                        remaining -= 1
                        totalIterations += 1
                    }
                }
            } else {
                onNak(nakSn)
            }
        }
    }
}

internal object SecureRandomSource {
    private val random = java.security.SecureRandom()

    fun fill(bytes: ByteArray) {
        random.nextBytes(bytes)
    }

    fun nextUInt32(): Long = random.nextLong() and 0xFFFF_FFFFL
}

internal fun nowMicros(): Long = System.nanoTime() / 1000
internal fun nowMillis(): Long = System.nanoTime() / 1_000_000

internal fun readUInt16(packet: ByteArray, offset: Int): Int =
    ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)

internal fun readUInt32(packet: ByteArray, offset: Int): Long =
    ((packet[offset].toLong() and 0xFF) shl 24) or
        ((packet[offset + 1].toLong() and 0xFF) shl 16) or
        ((packet[offset + 2].toLong() and 0xFF) shl 8) or
        (packet[offset + 3].toLong() and 0xFF)

internal fun readInt64(packet: ByteArray, offset: Int): Long =
    ((packet[offset].toLong() and 0xFF) shl 56) or
        ((packet[offset + 1].toLong() and 0xFF) shl 48) or
        ((packet[offset + 2].toLong() and 0xFF) shl 40) or
        ((packet[offset + 3].toLong() and 0xFF) shl 32) or
        ((packet[offset + 4].toLong() and 0xFF) shl 24) or
        ((packet[offset + 5].toLong() and 0xFF) shl 16) or
        ((packet[offset + 6].toLong() and 0xFF) shl 8) or
        (packet[offset + 7].toLong() and 0xFF)

internal fun writeUInt16(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value shr 8).toByte()
    bytes[offset + 1] = value.toByte()
}

internal fun writeUInt32(bytes: ByteArray, offset: Int, value: Long) {
    bytes[offset] = (value shr 24).toByte()
    bytes[offset + 1] = (value shr 16).toByte()
    bytes[offset + 2] = (value shr 8).toByte()
    bytes[offset + 3] = value.toByte()
}

internal fun writeInt64(bytes: ByteArray, offset: Int, value: Long) {
    bytes[offset] = (value shr 56).toByte()
    bytes[offset + 1] = (value shr 48).toByte()
    bytes[offset + 2] = (value shr 40).toByte()
    bytes[offset + 3] = (value shr 32).toByte()
    bytes[offset + 4] = (value shr 24).toByte()
    bytes[offset + 5] = (value shr 16).toByte()
    bytes[offset + 6] = (value shr 8).toByte()
    bytes[offset + 7] = value.toByte()
}

/**
 * Which of the resolved addresses to use.
 *
 * The setting exists because some carriers and receivers handle IPv6 worse than
 * IPv4 — the connection is established, but no data flows. Moblin has a separate
 * hostname-resolution strategy toggle for this; this does the same.
 *
 * @param preferIpv4 use the first IPv4 if available. If there is no IPv4,
 *  return the first available address: having no address is worse than using IPv6.
 */
fun pickAddress(addresses: List<java.net.InetAddress>, preferIpv4: Boolean): java.net.InetAddress? {
    if (addresses.isEmpty()) return null
    if (!preferIpv4) return addresses.first()
    return addresses.firstOrNull { it is java.net.Inet4Address } ?: addresses.first()
}
