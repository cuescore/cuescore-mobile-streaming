package app.brix.bonding

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class SrtDataPacket(payload: ByteArray) {
    var data: ByteArray = ByteArray(Srt.DATA_PACKET_HEADER_SIZE + payload.size).also { dst ->
        System.arraycopy(payload, 0, dst, Srt.DATA_PACKET_HEADER_SIZE, payload.size)
    }
    var sequenceNumber: Long = 0
    var createdAt: Long = 0
    var retransmittedAt: Long = 0

    fun setHeader(sequenceNumber: Long, now: Long, timestamp: Long, destinationSocketId: Long) {
        this.sequenceNumber = sequenceNumber
        createdAt = now
        writeUInt32(data, 0, sequenceNumber)
        writeUInt32(data, 4, 0xE000_0001L)
        writeUInt32(data, 8, timestamp)
        writeUInt32(data, 12, destinationSocketId)
    }

    fun setRetransmissionBit() {
        data[4] = (data[4].toInt() or 0x4).toByte()
    }
}

enum class SrtSenderState { CONNECTING, CONCLUSION_SENT, CONNECTED, DISCONNECTED }

class SrtSender(
    var streamId: String?,
    var latency: Int,
) {
    var onOutput: ((ByteArray) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    private val handshakeVersion4 = 4L
    private val handshakeVersion5 = 5L
    private val destinationSocket = 0L
    // Advertised MSS: matches the sender's real datagram budget (IP 20 + UDP 8
    // + SRT 16 + payload), so the peer and we never exceed a cellular MTU (H1).
    private val maximumTransmissionUnitSize = 1400L
    private val maximumFlowWindowSizeInPackets = 8192L
    private val socketId = SecureRandomSource.nextUInt32().let { if (it == 0L) 1L else it }

    private var nextSequenceNumber = SecureRandomSource.nextUInt32() % 10000
    private var peerDestinationSocketId = 0L
    private var lastConclusionPacket: ByteArray? = null
    private var provisionalPeerSocketId = 0L
    private var connectedFired = false
    private var conclusionSentEnteredTime = 0L
    private var lastProvisionalRetransmitUs = 0L
    // Written only under the monitor, but read without it in isConnected() and
    // isConnectedOrProvisional() — from the stats coroutine and the connection timeout
    // watchdog. The neighboring getters (rttMs, packetsInFlightCount, sendRateMbps,
    // droppedPackets) are all @Synchronized; these two had fallen outside that rule.
    @Volatile
    private var state = SrtSenderState.DISCONNECTED
    private val packetsToSend = ArrayDeque<SrtDataPacket>()
    private val packetsInFlight = ArrayDeque<SrtDataPacket>()
    private val packetsInFlightBySequenceNumber = HashMap<Long, SrtDataPacket>()
    private val retransmitSequenceNumbers = LinkedHashSet<Long>()
    private var flowWindowOverflowCount = 0L
    private var rttUs = 0L

    /**
     * The last full ACK number already answered with ACKACK, and the last
     * confirmed packet number. The SRTLA server broadcasts each ACK on ALL
     * group links (PROTOCOL_SPEC §2), and we processed every copy: N
     * passes of removeIf over the table and N ACKACKs for one number — about 90 extra
     * sendto calls per second over two links (audit 23.09). The receiver does not need a
     * duplicate ACKACK: it can no longer find that number.
     */
    private var lastAckAckNumber = -1L
    private var lastAckedSn = -1L

    /*Log.isLoggable reads a native system property; doing this for every ACK
     *  (~180/s with duplicates) is noticeable. Read it once per second, as already
     *  done in SrtlaConnection. */
    private var verboseAcks = false
    private var verboseCheckedAtUs = 0L
    private var latestReceivedPacketTime = 0L
    private var latestOutputPacketsTime = 0L

    /**
     * Packets ready to send. Collected under the SrtSender monitor, then
     * written to the socket without it.
     *
     * Why: writes used to happen directly inside synchronized methods, so the system
     * send call held the shared monitor. It is contended by the media thread
     * (`enqueue` ~1300 times/s, `send` ~75 times/s) and both read threads (`input`).
     * While one writes to the socket, all the others wait. Measurement on 31.08: read threads at
     * ~25% CPU each while encoding at 22%.
     *
     * The two buffers swap places to avoid allocating on every flush.
     */
    private var pendingOutput = ArrayList<ByteArray>()
    private var flushBuffer = ArrayList<ByteArray>()

    /*A separate flush lock serializes writes to the socket (packet order
     *  must be preserved) without blocking state operations. */
    private val outputLock = Any()
    private var startTime = 0L
    private var numberOfBytesSent = 0L
    private var mbpsSendRate = 0.0
    private var latestNumberOfBytesSentTime = 0L

    fun start() {
        synchronized(this) { startLocked() }
        flushOutput()
    }

    private fun startLocked() {
        // Do NOT log the full streamId: for SRTLA it contains ?srtauth=<key>,
        // which is the stream key. The “Srtla” tag is on the allowlist for logs
        // included in diagnostic reports, which people share with others — on 14.09 we removed
        // keys from settings but missed this path.
        // Presence and length are enough for debugging: they show
        // whether streamId was supplied and whether it was truncated.
        android.util.Log.w(
            "Srtla",
            "srt-sender: start() streamId: есть=${!streamId.isNullOrEmpty()} длина=${streamId?.length ?: 0}",
        )
        startTime = nowMicros()
        latestReceivedPacketTime = nowMicros()
        latestOutputPacketsTime = 0
        latestNumberOfBytesSentTime = nowMicros()
        packetsToSend.clear()
        packetsInFlight.clear()
        packetsInFlightBySequenceNumber.clear()
        retransmitSequenceNumbers.clear()
        nextSequenceNumber = SecureRandomSource.nextUInt32() % 10000
        peerDestinationSocketId = 0
        provisionalPeerSocketId = 0
        connectedFired = false
        conclusionSentEnteredTime = 0
        lastProvisionalRetransmitUs = 0
        rttUs = 0
        lastAckAckNumber = -1L
        lastAckedSn = -1L
        numberOfBytesSent = 0
        mbpsSendRate = 0.0
        setState(SrtSenderState.CONNECTING)
        outputPacket(createInductionHandshakePacket())
    }

    fun stop() {
        synchronized(this) { stopLocked() }
        flushOutput()
    }

    private fun stopLocked() {
        // Say goodbye properly: without an SRT SHUTDOWN the server-side
        // publisher lingers for its idle timeout, and a rapid Stop->Start
        // then looks like a duplicate publisher for the same stream key —
        // the server kills the NEW session with a SHUTDOWN ~4ms after its
        // handshake (field-verified: every restart within ~10s died).
        if (state == SrtSenderState.CONNECTED || state == SrtSenderState.CONCLUSION_SENT) {
            sendShutdown()
        }
        setDisconnected()
    }

    private fun sendShutdown() {
        val writer = PacketWriter()
        writer.write(createCommonControlPacketHeader(
            Srt.PacketType.SHUTDOWN.rawValue, 0, timestamp(nowMicros()), peerDestinationSocketId,
        ))
        // Shutdown carries two uint32 fields (first/last sequence in flight);
        // zeros are accepted by libsrt-based receivers.
        writer.writeUInt32(0)
        writer.writeUInt32(0)
        outputPacket(writer.toByteArray())
    }

    fun newDataPacket(payload: ByteArray): SrtDataPacket = SrtDataPacket(payload)

    fun enqueue(packet: SrtDataPacket, now: Long) {
        synchronized(this) { enqueueLocked(packet, now) }
        flushOutput()
    }

    private fun enqueueLocked(packet: SrtDataPacket, now: Long) {
        if (state != SrtSenderState.CONNECTED && state != SrtSenderState.CONCLUSION_SENT) return
        packet.setHeader(
            sequenceNumber = getNextSequenceNumber(),
            now = now,
            timestamp = timestamp(now),
            destinationSocketId = peerDestinationSocketId,
        )
        packetsToSend.addLast(packet)
    }

    fun send(now: Long) {
        synchronized(this) { sendLocked(now) }
        flushOutput()
    }

    private fun sendLocked(now: Long) {
        checkIfConnected(now)
        keepConclusionRetransmit(now)
        if (state != SrtSenderState.CONNECTED && state != SrtSenderState.CONCLUSION_SENT) return
        // dropOldPackets runs BEFORE the pump: previously the pump emitted only part of the queue,
        // so anything it had not sent survived until the drop check. Now it
        // empties the queue completely, so nothing remains to drop afterward —
        // stale media would simply go out on the live stream. Drop what is already useless
        // to the receiver first, then send the rest.
        dropOldPackets(now)
        outputPackets(now)
        enforceFlowWindow()
        updateSendRate(now)
    }

    fun input(packet: ByteArray) {
        synchronized(this) { inputLocked(packet) }
        flushOutput()
    }

    private fun inputLocked(packet: ByteArray) {
        latestReceivedPacketTime = nowMicros()
        handleControlPacket(packet, latestReceivedPacketTime)
    }

    private fun setDisconnected() {
        if (state == SrtSenderState.DISCONNECTED) return
        setState(SrtSenderState.DISCONNECTED)
        onDisconnected?.invoke()
    }

    private fun setState(newState: SrtSenderState) {
        state = newState
    }

    private fun timestamp(now: Long): Long = (now - startTime) and 0xFFFF_FFFFL

    private fun getNextSequenceNumber(): Long {
        val sn = nextSequenceNumber
        nextSequenceNumber = (nextSequenceNumber + 1) and 0x7FFF_FFFFL
        return sn
    }

    private fun checkIfConnected(now: Long) {
        // CONCLUSION_SENT expects inbound silence until the server reacts to
        // our provisional media: the no-inbound grace window starts from the
        // moment we enter the mode, not from the last received packet.
        if (state == SrtSenderState.CONCLUSION_SENT) {
            if (conclusionSentEnteredTime == 0L) conclusionSentEnteredTime = now
            if (latestReceivedPacketTime < conclusionSentEnteredTime) {
                latestReceivedPacketTime = conclusionSentEnteredTime
            }
        } else {
            conclusionSentEnteredTime = 0L
        }
        // A dead provisional path must be abandoned FAST: the receiver reaps
        // its publisher after ~5s without media anyway, so waiting the full
        // 5s only delays the (bounce-based) recovery. ACKs on a live path
        // arrive within one RTT (~0.1-0.3s on cellular); 2.5s covers even
        // heavy loss.
        val inboundTimeoutUs =
            if (state == SrtSenderState.CONCLUSION_SENT) 2_500_000L else 5_000_000L
        if (now - latestReceivedPacketTime > inboundTimeoutUs) {
            android.util.Log.w("Srtla", "srt-sender: DISCONNECTED (no packets ${(now - latestReceivedPacketTime) / 1000}ms) state=$state")
            setDisconnected()
        }
    }

    /**
     * Safety net: enforce the flow window declared in the handshake so memory
     * cannot grow unbounded if ACKs stop entirely on a dead path. Every forced
     * drop is counted (see [droppedPackets]) so the loss is visible to the ABR
     * and telemetry, rather than a silent media gap (H6).
     */
    private fun enforceFlowWindow() {
        while (packetsInFlight.size >= maximumFlowWindowSizeInPackets) {
            val dropped = packetsInFlight.removeFirst()
            packetsInFlightBySequenceNumber.remove(dropped.sequenceNumber)
            flowWindowOverflowCount++
            if (flowWindowOverflowCount % 100 == 1L) {
                android.util.Log.w("Srtla", "flow window overflow: $flowWindowOverflowCount packets force-dropped")
            }
        }
    }

    private fun dropOldPackets(now: Long) {
        // NOTE: un-ACKed in-flight packets must NOT be evicted by age. They stay
        // available for retransmission until the receiver ACKs them (SRT
        // semantics); dropping them turns recoverable loss into permanent loss
        // (H6). Memory is bounded by the flow window (enforceFlowWindow), which
        // counts every forced drop so it can be surfaced to the ABR. Only the
        // not-yet-sent queue is aged out.
        val toSendThreshold = latency * 1000L * 5 / 4
        while (packetsToSend.isNotEmpty() &&
            now - packetsToSend.first().createdAt > toSendThreshold
        ) {
            packetsToSend.removeFirst()
        }
    }

    /**
     * Flush everything queued: first the due retransmissions, then new
     * packets.
     *
     * The old version emitted at most ten packets per pass
     * (`count = maxOf(count / 10, minOf(count, 10))`) and used a 2 ms timer.
     * `send()` is called from the media thread about 75 times per second, while this bitrate
     * requires ~1300 packets per second, or 17 per call — ten was not enough, and the pump was
     * also triggered from BOTH read threads,
     * for every incoming control packet. Our media was sent in sync with
     * other peers’ acknowledgements, and socket writes held the shared SrtSender monitor,
     * contended by three threads (31.08 measurement: one packet takes ~90
     * µs to send; each read thread uses ~25% CPU).
     *
     * The amount is bounded by the queue snapshot at entry: anything added to the retransmit
     * queue during the pass goes out on the next pass instead of looping forever.
     */
    private fun outputPackets(now: Long) {
        // Retransmissions go first, all in one pass through the queue.
        //
        // This used to be quadratic. `retransmitPacketIfNeeded` sent ONE
        // packet, but could scan the entire queue (up to 1000) to do so,
        // copy skipped entries to a new ArrayList while boxing them as Longs and return
        // them with addAll — once per packet in a pump pass. While
        // `isSnAcked` was broken (until e591dfe), retransmissions effectively did not work and
        // the defect stayed hidden: only 0–3 packets per second were resent. After the fix, that became
        // 3–95, making this a hot path, and it runs exactly during loss —
        // when there is least headroom.
        //
        // Now there is one pass: skipped entries remain in the queue IN THEIR ORIGINAL POSITIONS
        // (packet numbers are no longer reordered), with no extra allocations.
        retransmitEligible(now)
        // The amount is bounded by the queue snapshot at entry: anything added to it
        // during the pass goes out on the next pass instead of looping forever.
        var budget = packetsToSend.size
        while (budget-- > 0) {
            val packet = packetsToSend.removeFirstOrNull() ?: return
            sendPacket(packet)
        }
    }

    /**
     * Retransmit all numbers that are eligible, in one pass.
     *
     * Numbers whose retransmission is still within the RTT window remain in the queue and are NOT
     * discarded: otherwise a repeated NAK arriving within one RTT would silently
     * lose the packet from the queue, and it would never be retransmitted (C4). A number
     * that is no longer in flight has already been acknowledged — remove the stale request.
     */
    private fun retransmitEligible(now: Long) {
        if (retransmitSequenceNumbers.isEmpty()) return
        val it = retransmitSequenceNumbers.iterator()
        while (it.hasNext()) {
            val sn = it.next()
            val packet = packetsInFlightBySequenceNumber[sn]
            if (packet == null) {
                it.remove()
                continue
            }
            if (packet.retransmittedAt != 0L && now - packet.retransmittedAt < rttUs) continue
            it.remove()
            packet.retransmittedAt = now
            packet.setRetransmissionBit()
            outputPacket(packet.data)
        }
    }

    private fun sendPacket(packet: SrtDataPacket) {
        outputPacket(packet.data)
        packetsInFlight.addLast(packet)
        packetsInFlightBySequenceNumber[packet.sequenceNumber] = packet
    }

    private fun outputPacket(packet: ByteArray) {
        pendingOutput.add(packet)
        numberOfBytesSent += packet.size + 28
    }

    /*Send the queued data. Called ONLY outside the SrtSender monitor. */
    private fun flushOutput() {
        synchronized(outputLock) {
            while (true) {
                synchronized(this) {
                    if (pendingOutput.isEmpty()) return
                    val tmp = pendingOutput
                    pendingOutput = flushBuffer
                    flushBuffer = tmp
                }
                val out = onOutput
                for (i in flushBuffer.indices) out?.invoke(flushBuffer[i])
                flushBuffer.clear()
            }
        }
    }

    private fun createCommonControlPacketHeader(
        type: Int,
        typeSpecificInformation: Long,
        timestamp: Long,
        destinationSocketId: Long,
    ): ByteArray {
        val out = ByteArray(16)
        writeUInt16(out, 0, 0x8000 or type)
        writeUInt16(out, 2, 0)
        writeUInt32(out, 4, typeSpecificInformation)
        writeUInt32(out, 8, timestamp)
        writeUInt32(out, 12, destinationSocketId)
        return out
    }

    private fun createInductionHandshakePacket(): ByteArray {
        val writer = PacketWriter()
        writer.write(createCommonControlPacketHeader(
            Srt.PacketType.HANDSHAKE.rawValue, 0, timestamp(nowMicros()), destinationSocket,
        ))
        writer.writeUInt32(handshakeVersion4)
        writer.writeUInt16(0)
        writer.writeUInt16(2)
        writer.writeUInt32(nextSequenceNumber)
        writer.writeUInt32(maximumTransmissionUnitSize)
        writer.writeUInt32(maximumFlowWindowSizeInPackets)
        writer.writeUInt32(1) // induction
        writer.writeUInt32(socketId)
        writer.writeUInt32(0)
        writer.write(byteArrayOf(1, 0, 0, 127, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        return writer.toByteArray()
    }

    private fun createConclusionHandshakePacket(peerSocketId: Long, synCookie: Long): ByteArray {
        val writer = PacketWriter()
        writer.write(createCommonControlPacketHeader(
            Srt.PacketType.HANDSHAKE.rawValue, 0, timestamp(nowMicros()), destinationSocket,
        ))
        writer.writeUInt32(handshakeVersion5)
        writer.writeUInt16(0)
        writer.writeUInt16(5)
        writer.writeUInt32(nextSequenceNumber)
        writer.writeUInt32(maximumTransmissionUnitSize)
        writer.writeUInt32(maximumFlowWindowSizeInPackets)
        writer.writeUInt32(0xFFFF_FFFFL) // conclusion
        writer.writeUInt32(peerSocketId)
        writer.writeUInt32(synCookie)
        writer.write(byteArrayOf(1, 0, 0, 127, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        writer.writeUInt16(1) // extension type (HSREQ)
        writer.writeUInt16(3) // extension length in 4-byte blocks
        writer.writeUInt32(0x0001_0503)
        writer.writeUInt32(0xBF)
        writer.writeUInt16(latency)
        writer.writeUInt16(latency)
        streamId?.let { id ->
            val encoded = encodeStreamId(id)
            writer.writeUInt16(5) // extension type (SID)
            writer.writeUInt16(encoded.size / 4)
            writer.write(encoded)
        }
        return writer.toByteArray()
    }

    private fun encodeStreamId(streamId: String): ByteArray {
        var data = streamId.toByteArray(Charsets.UTF_8)
        val padding = 4 - (data.size % 4)
        if (padding < 4) data = data + ByteArray(padding)
        for (i in data.indices step 4) {
            val t = data[i]
            data[i] = data[i + 3]
            data[i + 3] = t
            val u = data[i + 1]
            data[i + 1] = data[i + 2]
            data[i + 2] = u
        }
        return data
    }

    private fun handleControlPacket(packet: ByteArray, now: Long) {
        if (packet.size < 16) return
        val type = Srt.getControlPacketType(packet)
        // Validate destination socket ID for non-handshake control packets.
        // Stale/spoofed packets with wrong ID must not mutate transport state.
        //
        // The field at offset 12 is the SRT "Destination Socket ID": the socket
        // the packet is addressed TO. In a packet the receiver sends US that is
        // OUR [socketId] — never [peerDestinationSocketId], which is the id we
        // put in packets going the other way.
        //
        // Comparing against the peer's id therefore rejected EVERY non-handshake
        // control packet. The handshake is exempt, so a session still connected
        // and then went permanently deaf: ACKs never reached handleAckPacket, so
        // packetsInFlight was never drained, filled the 8192 flow window in ~15s,
        // and enforceFlowWindow began force-dropping ~550 packets/s — i.e. the
        // whole stream. Field capture 2026-08-31: 213 SRT-ACKs received by the
        // SRTLA layer, 0 processed here, 86 848 packets force-dropped, black
        // picture in OBS. rttUs also stayed 0, so ABR never ran either.
        if (type != Srt.PacketType.HANDSHAKE.rawValue) {
            val dstSocketId = readUInt32(packet, 12)
            // 0 is spec-legal on some control packets (no session assumed yet).
            if (dstSocketId != 0L && dstSocketId != socketId) {
                rejectedControlPackets++
                if (rejectedControlPackets % 100 == 1L) {
                    android.util.Log.w(
                        "Srtla",
                        "srt-sender: dropped control packet dst=$dstSocketId (ours=$socketId) " +
                            "type=$type total=$rejectedControlPackets",
                    )
                }
                return
            }
        }
        handleControlPacketByType(type, packet, now)
    }

    /** Control packets rejected by the destination-socket-id check. Logged
     *  rather than silently discarded: silence here is exactly what hid the bug
     *  above for weeks. */
    private var rejectedControlPackets = 0L

    private fun handleControlPacketByType(type: Int, packet: ByteArray, now: Long) {
        when (type) {
            Srt.PacketType.HANDSHAKE.rawValue -> handleHandshakePacket(packet)
            Srt.PacketType.KEEPALIVE.rawValue -> handleKeepAlivePacket()
            Srt.PacketType.ACK.rawValue -> handleAckPacket(packet, now)
            Srt.PacketType.NAK.rawValue -> handleNakPacket(packet)
            Srt.PacketType.SHUTDOWN.rawValue -> {
                // The receiver closed the session itself. When analyzing the log, distinguish this
                // from our inactivity timeout: the causes differ. Most often this looks like a stream key
                // already in use — there is already a publisher on the server
                // with the same streamid, and it terminates the new session immediately after
                // the handshake.
                android.util.Log.w("Srtla", "srt-sender: SHUTDOWN от приёмника state=$state")
                setDisconnected()
            }
        }
        // Do NOT start the pump from here: see outputPackets. Sending is paced by
        // media, not incoming acknowledgements.
    }

    private fun handleHandshakePacket(packet: ByteArray) {
        if (packet.size < 48) {
            android.util.Log.w("Srtla", "srt-sender: handshake packet too small size=${packet.size}")
            return
        }
        val handshakeType = readUInt32(packet, 36)
        val peerSocketId = readUInt32(packet, 40)
        val synCookie = readUInt32(packet, 44)
        android.util.Log.w(
            "Srtla",
            "srt-sender: HANDSHAKE recv size=${packet.size} hsType=0x${handshakeType.toString(16)} peerSocketId=$peerSocketId synCookie=$synCookie",
        )
        when (handshakeType) {
            1L -> handleHandshakeInduction(peerSocketId, synCookie)
            0xFFFF_FFFFL -> handleHandshakeConclusion(peerSocketId)
        }
    }

    private fun handleHandshakeInduction(peerSocketId: Long, synCookie: Long) {
        android.util.Log.w("Srtla", "srt-sender: handshake INDUCTION -> send conclusion")
        provisionalPeerSocketId = peerSocketId
        val conclusion = createConclusionHandshakePacket(peerSocketId, synCookie)
        lastConclusionPacket = conclusion
        outputPacket(conclusion)
    }

    /**
     * Re-send the SRT conclusion handshake packet without starting a new
     * induction. Used when the server's CONCLUSION response (which promotes us
     * to CONNECTED) is lost on a lossy downlink. Sending the conclusion again
     * keeps the SAME SRT session alive and makes the server re-answer CONCLUSION
     * — it does not create a fresh session, so it avoids tripping the server's
     * auth rate-limiter (unlike a full transport restart).
     */
    fun retransmitConclusion() {
        synchronized(this) { retransmitConclusionLocked() }
        flushOutput()
    }

    private fun retransmitConclusionLocked() {
        val conclusion = lastConclusionPacket ?: return
        if (state == SrtSenderState.CONNECTED) return
        android.util.Log.w("Srtla", "srt-sender: retransmit conclusion (state=$state)")
        outputPacket(conclusion)
    }

    /**
     * Enter provisional CONCLUSION_SENT mode: the server accepted the publisher
     * (we received INDUCTION and sent CONCLUSION), but its CONCLUSION reply was
     * lost. Allow early media to flow using the peer socket id learned from
     * INDUCTION. Fires onConnected so the media pipeline starts immediately —
     * the receiver reaps a publisher after ~5s without media, so waiting for a
     * CONCLUSION reply that may never arrive would lose the group anyway.
     *
     * NOTE: the induction-reply socket id is usually NOT the id the receiver's
     * SRT endpoint expects for media after full establishment (field-verified:
     * the CONCLUSION reply carries a different, correct id). Provisional media
     * may therefore be dropped by the receiver until the real CONCLUSION
     * arrives; keepConclusionRetransmit() keeps re-asking for it every second.
     */
    fun enterConclusionSent() {
        synchronized(this) { enterConclusionSentLocked() }
        flushOutput()
    }

    private fun enterConclusionSentLocked() {
        if (state != SrtSenderState.CONNECTING) return
        if (provisionalPeerSocketId == 0L) return
        android.util.Log.w("Srtla", "srt-sender: enter CONCLUSION_SENT (provisional media, dst=$provisionalPeerSocketId)")
        peerDestinationSocketId = provisionalPeerSocketId
        setState(SrtSenderState.CONCLUSION_SENT)
        fireConnectedOnce()
    }

    /**
     * While in provisional mode, re-send our CONCLUSION once per second: each
     * copy prompts the server to (re-)send its CONCLUSION reply carrying the
     * CORRECT media socket id. On arrival handleHandshakeConclusion upgrades
     * the destination and real media flow begins.
     */
    private fun keepConclusionRetransmit(now: Long) {
        if (state != SrtSenderState.CONCLUSION_SENT) return
        val conclusion = lastConclusionPacket ?: return
        if (lastProvisionalRetransmitUs == 0L) {
            // Anchor on the first media-pump tick of the provisional window.
            lastProvisionalRetransmitUs = now
            return
        }
        if (now - lastProvisionalRetransmitUs < 1_000_000) return
        lastProvisionalRetransmitUs = now
        android.util.Log.w("Srtla", "srt-sender: provisional re-ask conclusion (dst=$peerDestinationSocketId)")
        outputPacket(conclusion)
    }

    private fun handleHandshakeConclusion(peerSocketId: Long) {
        if (connectedFired && state == SrtSenderState.CONNECTED) {
            android.util.Log.w("Srtla", "srt-sender: handshake CONCLUSION after CONCLUSION_SENT upgrade -> CONNECTED (no double onConnected)")
            peerDestinationSocketId = peerSocketId
            setState(SrtSenderState.CONNECTED)
            lastConclusionPacket = null
            return
        }
        android.util.Log.w("Srtla", "srt-sender: handshake CONCLUSION -> CONNECTED")
        peerDestinationSocketId = peerSocketId
        setState(SrtSenderState.CONNECTED)
        lastConclusionPacket = null
        fireConnectedOnce()
    }

    private fun fireConnectedOnce() {
        if (connectedFired) return
        connectedFired = true
        onConnected?.invoke()
    }

    private fun handleKeepAlivePacket() {
        val keepAlive = createCommonControlPacketHeader(
            Srt.PacketType.KEEPALIVE.rawValue, 0, timestamp(nowMicros()), peerDestinationSocketId,
        )
        outputPacket(keepAlive)
    }

    private fun handleAckPacket(packet: ByteArray, now: Long) {
        if (packet.size < 20) return
        val lastAcknowledgedSequenceNumber = readUInt32(packet, 16)
        val before = packetsInFlight.size
        // A copy of the same ACK from another link: nothing new to acknowledge.
        if (lastAcknowledgedSequenceNumber != lastAckedSn) {
            removeAckedPackets(lastAcknowledgedSequenceNumber)
            lastAckedSn = lastAcknowledgedSequenceNumber
        }
        val typeSpecificInformation = readUInt32(packet, 4)
        // Only a "full" ACK (typeSpecificInformation != 0) carries RTT/
        // bandwidth stats and feeds updateSendRate()/rttUs — if the receiver
        // only ever sends light ACKs (or none at all), rttMs() stays 0
        // forever and AdaptiveBitrate.update() bails on its first line, so
        // ABR silently never runs.
        //
        // These two lines were unconditional while we tracked a socket-id bug. The bug
        // was found (5953995), but the lines remained: ~83 ACK/s × 2 lines, including text construction
        // and a system-log call. The 31.08 measurement showed packet handling
        // using three times more CPU than encoding — this cannot stay on the
        // hot path. Diagnostics remain available under the flag.
        if (now - verboseCheckedAtUs > 1_000_000L) {
            verboseCheckedAtUs = now
            verboseAcks = android.util.Log.isLoggable("Srtla", android.util.Log.VERBOSE)
        }
        if (verboseAcks) {
            android.util.Log.v(
                "Srtla",
                "srt-ack ackSn=$lastAcknowledgedSequenceNumber before=$before " +
                    "after=${packetsInFlight.size} tsi=$typeSpecificInformation rttUsBefore=$rttUs",
            )
        }
        if (typeSpecificInformation != 0L) {
            if (packet.size < 24) return
            if (typeSpecificInformation == lastAckAckNumber) return
            lastAckAckNumber = typeSpecificInformation
            rttUs = readUInt32(packet, 20)
            updateSendRate(now)
            val ackAck = createAckAckPacket(typeSpecificInformation)
            outputPacket(ackAck)
        }
    }

    private fun updateSendRate(now: Long) {
        val durationUs = now - latestNumberOfBytesSentTime
        if (durationUs <= 200_000) return
        latestNumberOfBytesSentTime = now
        val durationSeconds = durationUs / 1_000_000.0
        var latestMbpsSendRate = 8.0 * numberOfBytesSent / durationSeconds / 1_000_000
        numberOfBytesSent = 0
        // If the byte counter drops to ~0 between samples we would report 0,
        // collapsing the ABR's throughput (and its th2 threshold) so the bitrate
        // spirals down to the minimum. Floor the measurement to keep ABR healthy.
        if ((state == SrtSenderState.CONNECTED || state == SrtSenderState.CONCLUSION_SENT) &&
            latestMbpsSendRate < 1.0
        ) {
            latestMbpsSendRate = 1.0
        }
        // Return the interval rate as-is, without our own EWMA.
        //
        // Previously this used `0.7 * mbpsSendRate + 0.3 * latest`, and the series
        // was filtered TWICE: first here, then again in
        // AdaptiveBitrate.updateThroughput. The reference (belacoder) has one stage —
        // `srt_bstats(sock, &stats, 1)` returns the instantaneous rate over an interval,
        // and the controller does the smoothing. Our calculation above is that same
        // interval measurement (the durationUs <= 200_000 gate sets the interval), so
        // the second filter was redundant: it added lag, causing throughput to fall slowly,
        // lowering the Th2 threshold and pushing the controller to
        // cut the bitrate.
        //
        // The 1.0 Mbit/s clamp ABOVE REMAINS: it was added to break this
        // same feedback loop, and removing it at the same time would change two things at once.
        mbpsSendRate = latestMbpsSendRate
    }

    fun isConnected(): Boolean = state == SrtSenderState.CONNECTED

    /** CONNECTED or provisional CONCLUSION_SENT (media may already flow). */
    fun isConnectedOrProvisional(): Boolean =
        state == SrtSenderState.CONNECTED || state == SrtSenderState.CONCLUSION_SENT

    @Synchronized
    fun rttMs(): Double = rttUs / 1000.0

    @Synchronized
    fun packetsInFlightCount(): Int = packetsInFlight.size

    @Synchronized
    fun sendRateMbps(): Double = mbpsSendRate

    /** Datagrams force-dropped by the flow window (buffer felt on a dead path).
     *  Exposed so the ABR can see hard-loss signal vs. RTT-only inference. */
    @Synchronized
    fun droppedPackets(): Long = flowWindowOverflowCount

    private fun createAckAckPacket(ackNumber: Long): ByteArray {
        val out = ByteArray(20)
        val header = createCommonControlPacketHeader(
            Srt.PacketType.ACKACK.rawValue, 0, 0, peerDestinationSocketId,
        )
        System.arraycopy(header, 0, out, 0, 16)
        writeUInt32(out, 4, ackNumber)
        writeUInt32(out, 8, timestamp(nowMicros()))
        return out
    }

    private fun removeAckedPackets(lastAcknowledgedSequenceNumber: Long) {
        val index = packetsInFlight.indexOfFirst {
            !Srt.isSnAcked(it.sequenceNumber, lastAcknowledgedSequenceNumber)
        }
        val removeCount = if (index >= 0) index else packetsInFlight.size
        repeat(removeCount) {
            val p = packetsInFlight.removeFirst()
            packetsInFlightBySequenceNumber.remove(p.sequenceNumber)
        }
    }

    private fun handleNakPacket(packet: ByteArray) {
        Srt.processNak(packet) { sn ->
            if (retransmitSequenceNumbers.size < 1000 &&
                packetsInFlightBySequenceNumber.containsKey(sn)
            ) {
                retransmitSequenceNumbers.add(sn)
            }
        }
    }
}

private class PacketWriter {
    private val bos = ByteArrayOutputStream()
    private val dos = DataOutputStream(bos)

    fun write(bytes: ByteArray) {
        dos.write(bytes)
    }

    fun writeUInt16(value: Int) {
        dos.writeShort(value)
    }

    fun writeUInt32(value: Long) {
        dos.writeInt(value.toInt())
    }

    fun toByteArray(): ByteArray = bos.toByteArray()
}
