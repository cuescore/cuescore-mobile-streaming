package app.brix.streaming

import android.os.SystemClock
import android.util.Log
import app.brix.core.AbrAlgorithm

data class SrtStats(
    val rttMs: Double,
    val packetsInFlight: Double,
    val mbpsSendRate: Double,
    val latencyMs: Int,
    /** Startup grace window (Moblin "relaxed"): doubles the fast-decrease
     *  buffer threshold so initial buffer noise doesn't slam to minimum. */
    val relaxed: Boolean = false,
    /** Datagrams force-dropped by the SRT flow window (hard loss signal). */
    val droppedPackets: Long = 0,
)

/** Internal controller state, exposed to tests. See [AdaptiveBitrateSrtBelabox.debugState]. */
internal data class AbrDebugState(
    val rttAverage: Double,
    val rttMin: Double,
    val rttJitter: Double,
    val rttAverageDelta: Double,
    val throughput: Double,
    val sendBufferSizeAverage: Double,
    val sendBufferSizeJitter: Double,
)

class AdaptiveBitrateSrtBelabox(
    private var targetBitrate: Long,
    private var minimumBitrate: Long = 250_000,
    private var initialBitrate: Long = 2_500_000,
    // Clock is injectable so unit tests can drive time deterministically.
    // Production uses elapsedRealtime (monotonic uptime) instead of
    // currentTimeMillis: a system clock jump (NTP correction, user changing
    // the time) would otherwise shift the rate-limit windows
    // (nextBitrateIncrTime/DecrTime) and stall or spike the bitrate.
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    /** Aggressiveness profile (Moblin-aligned): BELABOX (default), FAST_IRL
     *  cuts harder/faster, SLOW_IRL gentler. CUSTOM keeps BELABOX reaction but
     *  honors the user's target/min (already applied). */
    private var algorithm: AbrAlgorithm = AbrAlgorithm.BELABOX,
) {
    // Read outside the monitor (see update)—the subscription is set up from another thread.
    @Volatile
    var onBitrateChange: ((Long) -> Unit)? = null

    private fun cutFactor(): Double = when (algorithm) {
        AbrAlgorithm.FAST_IRL -> 1.5
        AbrAlgorithm.SLOW_IRL -> 0.5
        else -> 1.0
    }

    /** Live algorithm switch without resetting the running bitrate. */
    @Synchronized
    fun setAlgorithm(a: AbrAlgorithm) {
        algorithm = a
    }

    private var currentBitrate = initialBitrate

    // Called from encoder thread (configure) and ABR loop (update) — plain
    // Double accumulators tear without a lock.
    @Synchronized
    fun configure(target: Long, min: Long, initial: Long) {
        targetBitrate = target
        minimumBitrate = min
        initialBitrate = initial
        currentBitrate = initial
        sendBufferSizeAverage = 0.0
        sendBufferSizeJitter = 0.0
        prevSendBufferSize = 0.0
        rttAverage = 0.0
        rttAverageDelta = 0.0
        prevRtt = 300.0
        rttMin = 200.0
        rttJitter = 0.0
        throughput = 0.0
        nextBitrateIncrTime = 0L
        nextBitrateDecrTime = 0L
        consecutiveBadHigh = 0
        consecutiveBadMid = 0
        prevDroppedPackets = 0L
    }

    /** Live update of ceiling/floor without resetting the running bitrate
     *  (so a settings change mid-stream does not cause a bitrate jump). */
    @Synchronized
    fun updateLimits(target: Long, min: Long) {
        targetBitrate = target
        minimumBitrate = min.coerceAtMost(target)
    }

    private var sendBufferSizeAverage = 0.0
    private var sendBufferSizeJitter = 0.0
    private var prevSendBufferSize = 0.0
    private var rttAverage = 0.0
    private var rttAverageDelta = 0.0
    private var prevRtt = 300.0
    private var rttMin = 200.0
    private var rttJitter = 0.0
    private var throughput = 0.0
    private var nextBitrateIncrTime = 0L
    private var lastAbrLogTime = 0L
    private var nextBitrateDecrTime = 0L
    // Consecutive samples above the hard "slam" threshold. A single RTT blip is
    // common on cellular; requiring several consecutive samples before cutting
    // hard removes the saw-tooth between minimum and target.
    private var consecutiveBadHigh = 0
    // Same for the middle branch. packetsInFlight is sampled instantaneously once every
    // 200 ms, and the value oscillates between acknowledgments: in the field on 31.08,
    // an oscillation from 15 -> 1055 was observed with steady RTT (47–58 ms) and zero packet loss,
    // because one acknowledgment removes hundreds of packets from flight at once
    // (srt-ack before=419 after=247). One such sample above the threshold halved the
    // bitrate, and the picture broke up into artifacts during fast motion despite a
    // perfectly healthy network. Require the threshold to remain exceeded.
    private var consecutiveBadMid = 0
    private var prevDroppedPackets = 0L

    /**
     * Runs one controller tick and, if the bitrate changed, calls
     * [onBitrateChange]—**after leaving the monitor**. The subscriber invokes
     * `setVideoBitrateOnFly()`, which goes to the encoder, so there is no need to hold
     * the controller lock during this call: another thread could not even
     * read `currentBitrateKbps()` during that time.
     */
    fun update(stats: SrtStats) {
        val newBitrate = updateLocked(stats) ?: return
        onBitrateChange?.invoke(newBitrate)
    }

    /** @return the new bitrate if it changed, otherwise null. */
    @Synchronized
    private fun updateLocked(stats: SrtStats): Long? {
        if (stats.rttMs == 0.0) return null
        val now = clock()

        val sendBufferSize = stats.packetsInFlight
        updateSendBufferSizeAverage(sendBufferSize)
        updateSendBufferSizeJitter(sendBufferSize)
        val rtt = stats.rttMs
        updateRttAverage(rtt)
        val deltaRtt = updateAverageRttDelta(rtt)
        updateRttMin(rtt)
        updateRttJitter(deltaRtt)
        updateThroughput(stats.mbpsSendRate)
        val srtLatency = stats.latencyMs.toDouble()

        var bitrate = currentBitrate
        val sendBufferSizeTh3 = (sendBufferSizeAverage + sendBufferSizeJitter) * 4
        var sendBufferSizeTh2 = maxOf(
            50.0,
            sendBufferSizeAverage + maxOf(sendBufferSizeJitter * 3.0, sendBufferSizeAverage),
        )
        sendBufferSizeTh2 = minOf(
            sendBufferSizeTh2,
            rttToSendBufferSize(srtLatency / 2, throughput),
        )
        if (stats.relaxed) sendBufferSizeTh2 *= 2
        val sendBufferSizeTh1 = maxOf(50.0, sendBufferSizeAverage + sendBufferSizeJitter * 2.5)
        val rttThMax = rttAverage + maxOf(rttJitter * 4, rttAverage * 15 / 100)
        val rttThMin = rttMin + maxOf(1.0, rttJitter * 2)

        var branch = "none"
        // Hard-congestion: several consecutive samples above the high threshold,
        // or an actual forced flow-window drop. Cut proportionally (half), not to
        // the floor, so a blip can't snap the stream to minimum (H7).
        val highBad = rtt >= srtLatency / 3 || sendBufferSize > sendBufferSizeTh3
        consecutiveBadHigh = if (highBad) consecutiveBadHigh + 1 else 0
        val midBad = rtt > srtLatency / 5 || sendBufferSize > sendBufferSizeTh2
        consecutiveBadMid = if (midBad) consecutiveBadMid + 1 else 0
        val flowDrop = stats.droppedPackets - prevDroppedPackets
        prevDroppedPackets = stats.droppedPackets
        if (bitrate > minimumBitrate && (consecutiveBadHigh >= 3 || flowDrop > 0)) {
            val cut = if (flowDrop > 0) bitrate / 4 else bitrate / 2
            bitrate = maxOf(bitrate - (cut * cutFactor()).toLong(), minimumBitrate)
            nextBitrateDecrTime = now + 250
            branch = if (flowDrop > 0) "drop" else "halfBig"
        } else if (now > nextBitrateDecrTime && midBad && consecutiveBadMid >= CONSECUTIVE_BAD_MID) {
            bitrate -= ((100_000 + bitrate / 10) * cutFactor()).toLong()
            nextBitrateDecrTime = now + 250
            branch = "decBig"
        } else if (now > nextBitrateDecrTime && (rtt > rttThMax || sendBufferSize > sendBufferSizeTh1)) {
            bitrate -= (100_000 * cutFactor()).toLong()
            nextBitrateDecrTime = now + 200
            branch = "dec"
        } else if (now > nextBitrateIncrTime && rtt < rttThMin && rttAverageDelta < 0.01) {
            bitrate += 100_000 + bitrate / 30
            nextBitrateIncrTime = now + 400
            branch = "inc"
        }

        bitrate = bitrate.coerceIn(minimumBitrate, targetBitrate)
        // Log only actual bitrate changes, throttled to one line per second:
        // a 200ms debug flood rotates the whole logcat buffer in minutes and
        // buries real events (field finding, C1).
        if (bitrate != currentBitrate && now - lastAbrLogTime >= 1000) {
            lastAbrLogTime = now
            android.util.Log.d(
                "Srtla",
                "abr $branch kbps=${currentBitrate / 1000}->${bitrate / 1000} " +
                    "rtt=${rtt.toInt()} pif=${sendBufferSize.toInt()} " +
                    "mbps=${stats.mbpsSendRate.toInt()}",
            )
        }
        if (bitrate == currentBitrate) return null
        currentBitrate = bitrate
        return bitrate
    }

    @Synchronized
    fun currentBitrateKbps(): Long = currentBitrate / 1000

    /**
     * Snapshot of internal state—tests only. Without it, the time
     * constants could not be verified: only `currentBitrateKbps()` was exposed, and the
     * period mismatch (coefficients for a 20 ms cycle applied to a 200 ms cycle)
     * went unnoticed for exactly that reason.
     */
    @Synchronized
    internal fun debugState(): AbrDebugState = AbrDebugState(
        rttAverage = rttAverage,
        rttMin = rttMin,
        rttJitter = rttJitter,
        rttAverageDelta = rttAverageDelta,
        throughput = throughput,
        sendBufferSizeAverage = sendBufferSizeAverage,
        sendBufferSizeJitter = sendBufferSizeJitter,
    )

    private companion object {
        /** How many consecutive samples must exceed the middle threshold before
         *  reducing. Polling happens every 200 ms, so two samples mean 400 ms: a single
         *  spike is filtered out, while a real bottleneck persists and is reduced sooner
         *  than in the hard branch (which requires three samples). */
        const val CONSECUTIVE_BAD_MID = 2

        /*
         * Smoothing coefficients have been recalculated for OUR polling interval.
         *
         * The algorithm is a port of belacoder, which calls `update_bitrate()` every
         * 20 ms (`BITRATE_UPDATE_INT 20`). Our loop in `SrtlaStream.startAdaptiveLoop`
         * ticks every 200 ms, ten times less often, but the coefficients had been
         * copied verbatim—which means EVERY time constant ended up
         * ten times longer than intended: RTT averaging of 20 s instead of 2,
         * RTT delta of 1 s instead of 0.1, and throughput of 6.6 s instead of 0.66.
         *
         * To preserve the time constant when the interval is N times longer,
         * decay is raised to the Nth power: d_new = d_old^N. Hence 0.99^10 ≈ 0.90,
         * 0.8^10 ≈ 0.11, 0.97^10 ≈ 0.74, 1.001^10 ≈ 1.01.
         *
         * The biggest cost came from `RTT_MIN_CREEP`: it creeps upward and
         * sets `rttThMin`—the only threshold for the `inc` growth branch. A pinned
         * `rttMin` kept the growth branch closed.
         */
        private const val BUFFER_AVG_DECAY = 0.90
        private const val RTT_AVG_DECAY = 0.90
        private const val RTT_DELTA_DECAY = 0.11
        private const val THROUGHPUT_DECAY = 0.74
        private const val RTT_MIN_CREEP = 1.01

        /** Decay of peak jitter estimates (buffer and RTT) is also per-tick,
         *  and was also ten times slower than intended: a spike took
         *  twenty seconds to dissipate instead of two. */
        private const val JITTER_DECAY = 0.90
    }



    private fun rttToSendBufferSize(rtt: Double, throughput: Double): Double =
        (throughput / 8) * rtt / 1316

    private fun updateSendBufferSizeAverage(v: Double) {
        sendBufferSizeAverage = sendBufferSizeAverage * BUFFER_AVG_DECAY + v * (1 - BUFFER_AVG_DECAY)
    }

    private fun updateSendBufferSizeJitter(v: Double) {
        sendBufferSizeJitter *= JITTER_DECAY
        val delta = v - prevSendBufferSize
        if (delta > sendBufferSizeJitter) sendBufferSizeJitter = delta
        prevSendBufferSize = v
    }

    private fun updateRttAverage(rtt: Double) {
        rttAverage = if (rttAverage == 0.0) {
            rtt
        } else {
            rttAverage * RTT_AVG_DECAY + (1 - RTT_AVG_DECAY) * rtt
        }
    }

    private fun updateAverageRttDelta(rtt: Double): Double {
        val delta = rtt - prevRtt
        rttAverageDelta = rttAverageDelta * RTT_DELTA_DECAY + delta * (1 - RTT_DELTA_DECAY)
        prevRtt = rtt
        return delta
    }

    private fun updateRttMin(rtt: Double) {
        rttMin *= RTT_MIN_CREEP
        if (rtt != 100.0 && rtt < rttMin && rttAverageDelta < 1.0) rttMin = rtt
    }

    private fun updateRttJitter(deltaRtt: Double) {
        rttJitter *= JITTER_DECAY
        if (deltaRtt > rttJitter) rttJitter = deltaRtt
    }

    private fun updateThroughput(mbps: Double) {
        throughput *= THROUGHPUT_DECAY
        throughput += (mbps * 1000.0 * 1000.0 / 1024.0) * (1 - THROUGHPUT_DECAY)
    }
}
