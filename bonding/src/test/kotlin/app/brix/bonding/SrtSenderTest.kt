package app.brix.bonding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtSenderTest {

    private fun setFieldLong(target: Any, name: String, value: Long) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.setLong(target, value)
    }

    @Test
    fun `handshake conclusion connects and data packets get increasing seq`() {
        val h = SrtSenderHarness()
        h.connect()

        assertTrue("expected onConnected after handshake conclusion", h.connected)
        h.setNextSequenceNumber(0)
        h.enqueueAndSend(3)

        assertEquals(3, h.dataPackets().size)
        assertEquals(listOf(0L, 1L, 2L), h.dataPackets().map { h.readSn(it) })
        assertEquals(3, h.sender.packetsInFlightCount())
    }

    @Test
    fun `ack with last seq clears the whole in-flight window`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        h.enqueueAndSend(5)
        assertEquals(5, h.sender.packetsInFlightCount())
        h.clearOutput()

        h.sender.input(h.createAck(lastAckSn = 5L))

        assertEquals(0, h.sender.packetsInFlightCount())
    }

    @Test
    fun `future ack ahead of all in-flight also clears everything`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        h.enqueueAndSend(5)
        h.clearOutput()

        h.sender.input(h.createAck(lastAckSn = 100_000L))

        assertEquals(0, h.sender.packetsInFlightCount())
    }

    @Test
    fun `ack with rtt triggers ackack and updates rtt`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        h.enqueueAndSend(5)
        h.clearOutput()

        h.sender.input(h.createAck(lastAckSn = 4L, rttUs = 30_000L))

        assertEquals(30.0, h.sender.rttMs(), 0.001)
        assertEquals(1, h.controlPacketsOfType(Srt.PacketType.ACKACK.rawValue).size)
    }

    /** Audit 23.09: the SRTLA server sends every ACK over all links in the group.
      *  A second ACKACK for a copy of the same number is unnecessary. */
    @Test
    fun `duplicate ack copies from other links get a single ackack`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        h.enqueueAndSend(5)
        h.clearOutput()

        val ack = h.createAck(lastAckSn = 4L, rttUs = 30_000L)
        h.sender.input(ack)
        h.sender.input(ack)
        h.sender.input(ack)

        assertEquals(1, h.controlPacketsOfType(Srt.PacketType.ACKACK.rawValue).size)

        h.enqueueAndSend(3)
        h.sender.input(h.createAck(lastAckSn = 7L, rttUs = 30_000L))
        assertEquals("a new ack number still gets its ackack", 2, h.controlPacketsOfType(Srt.PacketType.ACKACK.rawValue).size)
    }

    @Test
    fun `nak schedules retransmission of the lost packet`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        h.enqueueAndSend(3)
        h.clearOutput()

        h.sender.input(h.createNak(single = listOf(1L)))
        // input() no longer starts the pump: sending follows media timing, not the
        // timing of incoming acknowledgments (see outputPackets). Retransmission goes
        // out on the very next send() call, i.e. within the frame.
        h.sender.send(20_000L)

        val retransmits = h.dataPackets()
        assertEquals(1, retransmits.size)
        assertEquals(1L, h.readSn(retransmits[0]))
        assertTrue("expected retransmission bit set", h.isRetransmitted(retransmits[0]))
        assertTrue("lost packet stays in flight after retransmit", h.sender.packetsInFlightCount() >= 3)
    }

    @Test
    fun `nak within rtt keeps packet queued and retransmits again after rtt`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        h.enqueueAndSend(3)
        h.clearOutput()
        // Force a non-zero RTT so the within-RTT guard is exercised (C4).
        setFieldLong(h.sender, "rttUs", 1_000_000L)

        // First NAK → packet is retransmitted immediately.
        h.sender.input(h.createNak(single = listOf(1L)))
        h.sender.send(20_000L)
        assertEquals(1, h.dataPackets().size)
        h.clearOutput()

        // A second NAK arriving within one RTT must NOT drop the SN: it stays
        // queued (no retransmit yet, because we just sent it).
        h.sender.input(h.createNak(single = listOf(1L)))
        h.sender.send(21_000L)
        assertEquals("no retransmit within RTT window", 0, h.dataPackets().size)
        h.clearOutput()

        // After the RTT elapses the still-queued SN is retransmitted again.
        Thread.sleep(1100)
        h.sender.input(h.createNak(single = listOf(1L)))
        h.sender.send(nowMicros())
        val retransmits = h.dataPackets()
        assertEquals("retransmit again once RTT elapsed", 1, retransmits.size)
        assertEquals(1L, h.readSn(retransmits[0]))
        assertTrue("retransmission bit set", h.isRetransmitted(retransmits[0]))
    }

    /**
      * The pump pass retransmits ALL eligible numbers at once and in queue order.
     *
      * The old implementation sent one packet per call and rescanned the entire
      * retransmission queue each time, moving skipped packets through a temporary
      * list. Behavior is unchanged here; the test ensures a cost optimization does
      * not turn into a semantic change.
     */
    @Test
    fun `все запрошенные повторы уходят за один проход и по порядку`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        h.enqueueAndSend(200)
        h.clearOutput()

        val lost = listOf(3L, 17L, 42L, 99L, 150L, 199L)
        h.sender.input(h.createNak(single = lost))
        h.sender.send(20_000L)

        val sent = h.dataPackets().mapNotNull { h.readSn(it) }
        assertEquals(lost, sent)
        assertTrue("на всех должен стоять бит повтора", h.dataPackets().all { h.isRetransmitted(it) })
    }

    /**
      * The retransmission queue is limited to a thousand numbers (see handleNakPacket).
      * A full queue must be processed in one pump pass: it used to take quadratic
      * time in its length — scanning and copying the list for
      * EACH packet sent. This happened precisely during losses, when there is no
      * spare capacity anyway.
     */
    @Test
    fun `полная очередь повторов расходится за один проход`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        h.enqueueAndSend(1000)
        h.clearOutput()

        h.sender.input(h.createNak(ranges = listOf(NakRange(from = 0L, upTo = 999L))))
        h.sender.send(20_000L)

        val sent = h.dataPackets().mapNotNull { h.readSn(it) }
        assertEquals(1000, sent.size)
        assertEquals((0L until 1000L).toList(), sent)
    }

    /**
      * Packets skipped due to the RTT window are not discarded or moved to the end:
      * they stay in place and go out in the next pass, when the window
      * expires. Previously they were removed and restored via addAll, so the order
      * of packet numbers got scrambled.
     */
    @Test
    fun `пропущенные по RTT остаются в очереди и уходят следующим проходом`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        h.enqueueAndSend(10)
        h.clearOutput()
        setFieldLong(h.sender, "rttUs", 1_000_000L)

        // First pass: 1 and 2 are retransmitted and removed from the queue.
        h.sender.input(h.createNak(single = listOf(1L, 2L)))
        h.sender.send(20_000L)
        assertEquals(listOf(1L, 2L), h.dataPackets().mapNotNull { h.readSn(it) })
        h.clearOutput()

        // Second pass within the same RTT: 1 and 2 are requested again and must
        // stay in the queue, while fresh packets 5 and 6 are sent.
        h.sender.input(h.createNak(single = listOf(1L, 2L, 5L, 6L)))
        h.sender.send(21_000L)
        assertEquals(listOf(5L, 6L), h.dataPackets().mapNotNull { h.readSn(it) })
        h.clearOutput()

        // The RTT window has expired — the held-back 1 and 2 are sent, in their original order.
        h.sender.send(20_000L + 1_100_000L)
        assertEquals(listOf(1L, 2L), h.dataPackets().mapNotNull { h.readSn(it) })
    }

    /** A number already acknowledged by the receiver simply drops out of the
      *  retransmission queue: the packet is no longer in flight, so there is nothing to resend. */
    @Test
    fun `подтверждённый номер выпадает из очереди повторов`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        h.enqueueAndSend(10)
        h.clearOutput()
        setFieldLong(h.sender, "rttUs", 1_000_000L)

        h.sender.input(h.createNak(single = listOf(1L, 7L)))
        // The acknowledgment removes everything in flight through 5, inclusive, so 1 is no
        // longer needed, while 7 is still in flight.
        h.sender.input(h.createAck(lastAckSn = 6L))
        h.sender.send(20_000L)

        assertEquals(listOf(7L), h.dataPackets().mapNotNull { h.readSn(it) })
    }

    @Test
    fun `nak range expands into each sequence number`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        h.enqueueAndSend(5)
        h.clearOutput()

        h.sender.input(h.createNak(ranges = listOf(NakRange(from = 1L, upTo = 3L))))
        h.sender.send(20_000L)

        val packets = h.dataPackets()
        val retransmits = packets.map { h.readSn(it) }
        assertEquals(setOf(1L, 2L, 3L), retransmits.toSet())
        assertTrue(packets.all { h.isRetransmitted(it) })
    }

    @Test
    fun `nak range hard-limits at MAX_NAK_RANGE_SIZE`() {
        val received = mutableListOf<Long>()
        val packet = SrtTestPackets.nak(ranges = listOf(NakRange(from = 0L, upTo = 100_000L)))
        Srt.processNak(packet) { sn -> received.add(sn) }

        assertTrue(
            "must not exceed MAX_NAK_RANGE_SIZE (${Srt.MAX_NAK_RANGE_SIZE})",
            received.size <= Srt.MAX_NAK_RANGE_SIZE,
        )
    }

    @Test
    fun `nak range wrap-around iterates both sides of boundary`() {
        val received = mutableListOf<Long>()
        val packet = SrtTestPackets.nak(
            ranges = listOf(NakRange(from = 0x7FFF_FFF0L, upTo = 0x10L)),
        )
        Srt.processNak(packet) { sn -> received.add(sn) }

        assertTrue("should contain sns from start side", received.any { it >= 0x7FFF_FFF0L })
        assertTrue("should contain sns from end side", received.any { it <= 0x10L })
        assertTrue(
            "total must not exceed limit",
            received.size <= Srt.MAX_NAK_RANGE_SIZE,
        )
    }

    @Test
    fun `isSnAcked handles wrap-around at the 31-bit boundary`() {
        assertTrue("max sn acked by 0 (wrapped)", Srt.isSnAcked(0x7FFF_FFFFL, 0L))
        assertFalse("0 not acked by max sn (future direction)", Srt.isSnAcked(0L, 0x7FFF_FFFFL))
        assertFalse("номер из квитанции ещё НЕ получен", Srt.isSnAcked(0L, 0L))
        assertTrue("recently incremented seq is acked", Srt.isSnAcked(4L, 5L))
        assertFalse("next seq not acked by previous", Srt.isSnAcked(5L, 4L))
    }

    @Test
    fun `isSnAcked exclusive semantics — номер из квитанции ещё не подтверждён`() {
        // The old version of this test was named “inclusive semantics — exact ack
        // sn is acked” and enshrined the bug: the number in an SRT acknowledgment is the NEXT
        // EXPECTED packet; everything strictly before it has been acknowledged.
        //
        // Because of the inclusive comparison, we removed exactly the packet that
        // the receiver was missing. Field report 31.08: NAK always arrived for the
        // number one below the first in our buffer (sn=8421 with a window of
        // [8422..8661]); we did not find it, retransmission never went out, and the loss
        // became permanent.
        assertFalse("сам номер квитанции НЕ подтверждён", Srt.isSnAcked(100L, 100L))
        assertFalse("то же на больших значениях", Srt.isSnAcked(0x7FFF_FFFFL, 0x7FFF_FFFFL))
        assertTrue("всё строго меньше — подтверждено", Srt.isSnAcked(99L, 100L))
        assertFalse("всё больше — ещё нет", Srt.isSnAcked(101L, 100L))
    }

    @Test
    fun `sequence number wraps to zero at 31-bit boundary`() {
        val h = SrtSenderHarness()
        setFieldLong(h.sender, "nextSequenceNumber", 0x7FFF_FFFEL)
        h.connect()
        h.clearOutput()

        h.enqueueAndSend(3)

        assertEquals(listOf(0x7FFF_FFFEL, 0x7FFF_FFFFL, 0L), h.dataPackets().map { h.readSn(it) })
    }

    @Test
    fun `no incoming packets for 5s disconnects`() {
        val h = SrtSenderHarness()
        h.connect()
        assertFalse(h.disconnected)

        setFieldLong(h.sender, "latestReceivedPacketTime", 1_000_000L)
        h.sender.send(7_000_000L)

        assertTrue("expected disconnect after timeout", h.disconnected)
    }

    @Test
    fun `control packet with wrong destination socket ID is ignored`() {
        val h = SrtSenderHarness()
        h.connect()
        h.clearOutput()

        // peerDestinationSocketId is 0x1234_5678 after connect().
        // Send an ACK with wrong destination socket ID — must be ignored.
        val wrongAck = ByteArray(24)
        writeUInt16(wrongAck, 0, 0x8000 or Srt.PacketType.ACK.rawValue)
        writeUInt32(wrongAck, 12, 0xDEAD_BEEFL) // wrong dst socket ID
        writeUInt32(wrongAck, 16, 5L) // lastAckSn
        h.sender.input(wrongAck)

        // RTT must not be updated (packet was rejected).
        assertEquals(0.0, h.sender.rttMs(), 0.001)
    }

    @Test
    fun `conclusion_sent gets a fresh no-inbound grace window`() {
        val h = SrtSenderHarness()
        var connectedCalls = 0
        h.sender.onConnected = { connectedCalls++ }

        h.sender.start()
        // Induction reply received at t=1s; conclusion reply lost.
        h.sender.input(SrtTestPackets.handshakeInduction(peerSocketId = 0xAAAA_0007L, synCookie = 0xBBBB_0008L))
        setFieldLong(h.sender, "latestReceivedPacketTime", 1_000_000L)

        // Ladder exhausted at t=5.5s -> provisional mode. Inbound silence is
        // expected here, so the watchdog must not count from the last packet.
        h.sender.enterConclusionSent()
        assertEquals("provisional media keeps the session alive", 1, connectedCalls)
        assertFalse(h.disconnected)

        // Grace starts at the first watchdog check after entering (~t=9.5s):
        // still alive at +4s...
        h.sender.send(9_500_000L)
        assertFalse("within the CONCLUSION_SENT grace window", h.disconnected)

        // ...but a truly dead path is cut once the grace elapses.
        h.sender.send(15_000_000L)
        assertTrue("expected disconnect once the grace window expires", h.disconnected)
    }

    @Test
    fun `shutdown control packet disconnects`() {
        val h = SrtSenderHarness()
        h.connect()
        assertFalse(h.disconnected)

        h.sender.input(SrtTestPackets.shutdown(dstSocketId = h.ourSocketId()))

        assertTrue("expected disconnect on shutdown", h.disconnected)
    }

    @Test
    fun `send rate is floored above zero so ABR throughput stays healthy`() {
        val h = SrtSenderHarness()
        h.connect()

        var now = 1_000_000L
        repeat(40) {
            h.sender.send(now)
            now += 300_000L
        }

        assertTrue(
            "expected floored send rate near 1.0, got ${h.sender.sendRateMbps()}",
            h.sender.sendRateMbps() >= 0.9,
        )
    }

    // --- SRT handshake (Phase 4 / Stage 3) ---

    @Test
    fun `start sends an induction handshake`() {
        val h = SrtSenderHarness()
        h.sender.start()
        val handshakes = h.controlPacketsOfType(Srt.PacketType.HANDSHAKE.rawValue)
        assertEquals(1, handshakes.size)
        // Induction packet is 64 bytes.
        assertEquals(64, handshakes[0].size)
    }

    @Test
    fun `induction response triggers a conclusion and stores it`() {
        val h = SrtSenderHarness()
        h.sender.start()
        h.clearOutput()

        h.sender.input(SrtTestPackets.handshakeInduction(peerSocketId = 0x1111_2222L, synCookie = 0x3333_4444L))

        val handshakes = h.controlPacketsOfType(Srt.PacketType.HANDSHAKE.rawValue)
        assertEquals("expected one conclusion after induction", 1, handshakes.size)
        assertEquals("conclusion is 80 bytes (no stream id extension)", 80, handshakes[0].size)
        // Conclusion must be stored for later retransmit.
        assertNotNull(h.lastConclusionPacket())
        // We are still CONNECTING (server CONCLUSION not yet received).
        assertFalse(h.connected)
    }

    @Test
    fun `retransmitConclusion re-sends the stored conclusion without new induction`() {
        val h = SrtSenderHarness()
        h.sender.start()
        h.sender.input(SrtTestPackets.handshakeInduction(peerSocketId = 0x1111_2222L, synCookie = 0x3333_4444L))
        h.clearOutput()

        h.sender.retransmitConclusion()

        val handshakes = h.controlPacketsOfType(Srt.PacketType.HANDSHAKE.rawValue)
        assertEquals("retransmit sends one conclusion, not a new induction", 1, handshakes.size)
        assertEquals(80, handshakes[0].size)
    }

    @Test
    fun `retransmitConclusion is a no-op after CONNECTED`() {
        val h = SrtSenderHarness()
        h.connect() // -> CONNECTED
        h.clearOutput()
        h.sender.retransmitConclusion()
        assertEquals(0, h.output.size)
    }

    @Test
    fun `server conclusion promotes to CONNECTED and clears stored conclusion`() {
        val h = SrtSenderHarness()
        h.sender.start()
        h.sender.input(SrtTestPackets.handshakeInduction(peerSocketId = 0x1111_2222L, synCookie = 0x3333_4444L))
        assertFalse(h.connected)

        h.sender.input(SrtTestPackets.handshakeConclusion(peerSocketId = 0x5555_6666L))

        assertTrue("expected CONNECTED after server conclusion", h.connected)
        assertNull("stored conclusion cleared once connected", h.lastConclusionPacket())
    }

    @Test
    fun `onConnected and onDisconnected fire exactly once each`() {
        val h = SrtSenderHarness()
        var connectedCalls = 0
        var disconnectedCalls = 0
        h.sender.onConnected = { connectedCalls++ }
        h.sender.onDisconnected = { disconnectedCalls++ }

        h.sender.start()
        h.sender.input(SrtTestPackets.handshakeInduction(peerSocketId = 1L, synCookie = 2L))
        h.sender.input(SrtTestPackets.handshakeConclusion(peerSocketId = 3L))
        h.sender.input(SrtTestPackets.shutdown(dstSocketId = h.ourSocketId()))

        assertEquals(1, connectedCalls)
        assertEquals(1, disconnectedCalls)
    }

    @Test
    fun `stop sends a shutdown packet so the server releases the publisher`() {
        val h = SrtSenderHarness()
        h.connect()
        h.clearOutput()

        h.sender.stop()

        val shutdowns = h.controlPacketsOfType(Srt.PacketType.SHUTDOWN.rawValue)
        assertEquals("expected exactly one SHUTDOWN on graceful stop", 1, shutdowns.size)
    }

    @Test
    fun `stop without connection does not send a shutdown packet`() {
        val h = SrtSenderHarness()
        h.sender.start()
        h.clearOutput()

        // Still CONNECTING: the server has no publisher session to release.
        h.sender.stop()

        assertEquals(0, h.controlPacketsOfType(Srt.PacketType.SHUTDOWN.rawValue).size)
    }

    @Test
    fun `provisional mode keeps re-asking for the conclusion once per second`() {
        val h = SrtSenderHarness()
        h.sender.start()
        h.sender.input(SrtTestPackets.handshakeInduction(peerSocketId = 0xAAAA_0009L, synCookie = 0xBBBB_0010L))
        h.sender.enterConclusionSent()
        h.clearOutput()

        // First tick only anchors the retransmit timer; too early (<1s): none.
        h.sender.send(10_500_000L)
        assertEquals(0, h.handshakeOutput().size)

        // After >=1s since the anchor: exactly one conclusion re-ask.
        h.sender.send(12_000_000L)
        assertEquals(1, h.handshakeOutput().size)

        // The real CONCLUSION arrives -> upgrade, no further re-asks.
        h.sender.input(SrtTestPackets.handshakeConclusion(peerSocketId = 0xDDDD_0011L))
        h.clearOutput()
        h.sender.send(13_500_000L)
        assertEquals(0, h.handshakeOutput().size)
    }

    @Test
    fun `enterConclusionSent fires onConnected exactly once and late conclusion does not refire`() {
        val h = SrtSenderHarness()
        var connectedCalls = 0
        h.sender.onConnected = { connectedCalls++ }

        h.sender.start()
        h.sender.input(SrtTestPackets.handshakeInduction(peerSocketId = 0xAAAA_0001L, synCookie = 0xBBBB_0002L))
        assertEquals("not connected before enterConclusionSent", 0, connectedCalls)

        h.sender.enterConclusionSent()

        assertEquals("expected provisional connection (media may flow)", 1, connectedCalls)
        assertEquals(
            "peer socket id taken from induction reply",
            0xAAAA_0001L,
            getField(h.sender, "peerDestinationSocketId") as Long,
        )

        // Late server CONCLUSION arrives after provisional media started.
        h.sender.input(SrtTestPackets.handshakeConclusion(peerSocketId = 0xCCCC_0003L))

        assertEquals("onConnected must not fire twice", 1, connectedCalls)
        assertEquals(0xCCCC_0003L, getField(h.sender, "peerDestinationSocketId") as Long)
    }

    @Test
    fun `enterConclusionSent is a no-op without a stored peer socket id`() {
        val h = SrtSenderHarness()
        var connectedCalls = 0
        h.sender.onConnected = { connectedCalls++ }

        h.sender.start()
        h.sender.enterConclusionSent()

        assertEquals(0, connectedCalls)
    }

    private fun setField(target: Any, name: String, value: Any) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun getField(target: Any, name: String): Any? {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target)
    }

    // --- SRT stream id encoding (Stage 4) ---

    private fun encodeStreamId(id: String): ByteArray {
        val method = SrtSender::class.java.getDeclaredMethod("encodeStreamId", String::class.java)
        method.isAccessible = true
        return method.invoke(SrtSender(streamId = null, latency = 2000), id) as ByteArray
    }

    private fun decodeStreamId(encoded: ByteArray): String {
        // Moblin/our encoding swaps each 4-byte group: [0<->3, 1<->2].
        val bytes = encoded.copyOf()
        for (i in bytes.indices step 4) {
            val t = bytes[i]
            bytes[i] = bytes[i + 3]
            bytes[i + 3] = t
            val u = bytes[i + 1]
            bytes[i + 1] = bytes[i + 2]
            bytes[i + 2] = u
        }
        return String(bytes, Charsets.UTF_8).trimEnd('\u0000')
    }

    @Test
    fun `stream id ascii round trips`() {
        val id = "live/stream/test?srtauth=secret"
        assertEquals(id, decodeStreamId(encodeStreamId(id)))
    }

    @Test
    fun `stream id utf8 round trips`() {
        val id = "channel/привет?srtauth=ключ"
        assertEquals(id, decodeStreamId(encodeStreamId(id)))
    }

    @Test
    fun `stream id is padded to a 4-byte boundary`() {
        assertEquals(4, encodeStreamId("a").size)
        assertEquals(4, encodeStreamId("ab").size)
        assertEquals(4, encodeStreamId("abc").size)
        assertEquals(4, encodeStreamId("abcd").size)
        assertEquals(8, encodeStreamId("abcde").size)
    }

    // --- ACK edge cases (Stage 4) ---

    @Test
    fun `old ack does not clear a newer window`() {
        val h = SrtSenderHarness()
        h.connect()
        // Send seq 10..14.
        h.setNextSequenceNumber(10)
        h.enqueueAndSend(5)
        assertEquals(5, h.sender.packetsInFlightCount())
        h.clearOutput()

        // A stale ACK far below the window start must not clear anything.
        h.sender.input(h.createAck(lastAckSn = 0L))

        assertEquals("old ack must not clear in-flight", 5, h.sender.packetsInFlightCount())
    }

    @Test
    fun `ack before window start after partial ack keeps newer packets`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(10)
        h.enqueueAndSend(5) // in flight: 10,11,12,13,14
        // An acknowledgment numbered 12 means “everything BEFORE 12 received”, i.e. 10 and 11.
        // Packet 12 itself has not been received yet and must remain: otherwise we have no way
        // to satisfy a NAK for it (field bug 31.08, see Srt.isSnAcked).
        h.sender.input(h.createAck(lastAckSn = 12L))
        assertEquals("остаются 12,13,14", 3, h.sender.packetsInFlightCount())
        h.clearOutput()

        // A stale acknowledgment must not remove newer packets.
        h.sender.input(h.createAck(lastAckSn = 5L))

        assertEquals("remaining newer packets must survive stale ack", 3, h.sender.packetsInFlightCount())
    }

    // --- 31-bit wrap-around within window (Phase 4) ---

    @Test
    fun `removeAckedPackets handles entire window at 31-bit wrap boundary`() {
        val h = SrtSenderHarness()
        h.connect()
        // Place all 8 in-flight packets right at the 31-bit boundary:
        // 0x7FFF_FFF8 .. 0x7FFFFFFF
        h.setNextSequenceNumber(0x7FFF_FFF8L)
        h.enqueueAndSend(8)
        assertEquals(8, h.sender.packetsInFlightCount())
        h.clearOutput()

        // Acknowledgment 0x7FFFFFFF removes everything BEFORE it — seven packets; 0x7FFFFFFF
        // itself has not been received yet and remains.
        h.sender.input(h.createAck(lastAckSn = 0x7FFF_FFFFL))
        assertEquals("остаётся сам пограничный пакет", 1, h.sender.packetsInFlightCount())

        // Acknowledgment 0 is the wraparound transition: “waiting for 0” means 0x7FFFFFFF
        // was received. This is where the circular comparison is tested.
        h.sender.input(h.createAck(lastAckSn = 0L))
        assertEquals(0, h.sender.packetsInFlightCount())
    }

    // --- Duplicate INDUCTION in CONNECTED state (Phase 4) ---

    @Test
    fun `duplicate induction in CONNECTED state sends conclusion but no double onConnected`() {
        val h = SrtSenderHarness()
        var connectedCalls = 0
        h.sender.onConnected = { connectedCalls++; h.connected = true }
        h.connect()
        assertEquals(1, connectedCalls)
        h.clearOutput()

        // A stale INDUCTION arrives (e.g. from a delayed server probe).
        h.sender.input(SrtTestPackets.handshakeInduction(peerSocketId = 0xBBBB_0001L, synCookie = 0xCCCC_0002L))

        // Sender responds with a conclusion (current behavior — does not ignore it).
        val conclusions = h.handshakeOutput()
        assertEquals("duplicate induction triggers a conclusion response", 1, conclusions.size)
        assertEquals("no double onConnected from duplicate induction", 1, connectedCalls)

        // If the server answers that conclusion, session continues without error.
        h.sender.input(SrtTestPackets.handshakeConclusion(peerSocketId = 0xDDDD_0003L))
        assertTrue("sender remains connected after late conclusion", h.connected)
        assertEquals("onConnected still fired only once", 1, connectedCalls)
    }

    // --- Pacing / flow window (Phase 4) ---

    @Test
    fun `dropOldPackets keeps unacked in-flight packets so they stay retransmittable`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        h.enqueueAndSend(5) // in flight: createdAt=10_000..14_000
        assertEquals(5, h.sender.packetsInFlightCount())
        h.clearOutput()

        // Default latency=2000ms → old inFlight threshold was 3_000_000 us.
        // Advance clock 4s past creation: in-flight packets are stale by time
        // but must NOT be dropped — they stay available for retransmission
        // until the receiver ACKs them (dropping = permanent loss, H6).
        h.sender.send(14_000L + 4_000_000L)

        assertEquals("unacked in-flight packets retained", 5, h.sender.packetsInFlightCount())
    }

    @Test
    fun `dropOldPackets выбрасывает медиа, устаревшее сверх окна задержки`() {
        // The previous version of this test passed for the WRONG reason: packets did not
        // go out on the air because of the pump timer, not because they were
        // rejected as stale. The comment said “3M > threshold 2.5M”,
        // although the values in the test itself differed by 2000 microseconds.
        // Here the packets really are older than the window: latency of 2000 ms gives a
        // threshold of 2.5 s; send them 3 s after enqueueing.
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        val queuedAt = 10_000L
        repeat(5) {
            h.sender.enqueue(SrtDataPacket(ByteArray(8) { it.toByte() }), queuedAt + it)
        }
        h.clearOutput()
        h.sender.send(queuedAt + 3_000_000L)
        assertEquals("устаревшее медиа не должно уходить в эфир", 0, h.dataPackets().size)
    }

    @Test
    fun `send выпускает всю накопленную очередь за один проход`() {
        // The old pump sent no more than ten packets per pass and kept
        // a 2 ms timer. Since send() is called from the media thread only ~75 times
        // per second, while the target bitrate needs ~1300 packets per second,
        // a single pass was not enough — the pump was also triggered from both reader threads,
        // for each incoming control packet. Our media was paced by other parties' acknowledgments,
        // and socket writes happened under a shared monitor.
        //
        // The old test was named “rate-limits to at most once per
        // 2s” here and called 1000 microseconds “one second”: the time unit here
        // is microseconds (nowMicros, parameter nowUs in the harness), so the threshold was
        // 2 ms, not 2 s. The test codified an incorrect understanding of the unit.
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        val batch = 40
        repeat(batch) {
            h.sender.enqueue(SrtDataPacket(ByteArray(8) { it.toByte() }), 10_000L + it)
        }
        h.sender.send(10_000L + batch)
        assertEquals("насос обязан выпустить весь кадр за один проход", batch, h.dataPackets().size)
    }

    @Test
    fun `flow window does not enforce hard limit — documents current behavior`() {
        val h = SrtSenderHarness()
        h.connect()
        h.setNextSequenceNumber(0)
        // Enqueue way more than the declared 8192 window.
        repeat(100) {
            h.sender.enqueue(SrtDataPacket(ByteArray(8) { it.toByte() }), 10_000L + it * 1_000L)
        }
        h.sender.send(20_000L)

        // outputPackets sends max 10 per call (count/10 capped at 10).
        // All 100 are accepted into the queue — no enforcement of 8192 limit.
        // Verify that at least 10 moved to inFlight (no drop at enqueue).
        assertTrue(
            "flow window not enforced (100 packets accepted, 10 in flight)",
            h.sender.packetsInFlightCount() >= 10,
        )
    }
}