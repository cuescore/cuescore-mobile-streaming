package app.brix.streaming

import android.os.SystemClock

/**
 * Adaptive bitrate for transports over TCP (RTMP, WHIP).
 *
 * **Why a separate class instead of our [AdaptiveBitrateSrtBelabox].** That is a
 * port of belacoder, and five of its seven state variables describe RTT:
 * `rttAverage`, `rttMin`, `rttJitter`, `rttAverageDelta`, plus the
 * `rttThMax`/`rttThMin` thresholds. For RTMP over TCP, round-trip time is
 * unavailable in principle. Feeding in zeros makes the increase branch unconditional
 * (`rttMin` drifts to zero, the threshold collapses to 1 ms, and `rttAverageDelta`
 * is identically zero)—so the regulator would raise the bitrate in any network
 * conditions. This is not reuse; it disables half the control loop, at the cost of
 * changes to the shared class used by the working SRTLA path.
 *
 * **What this works with.** TCP does not lose packets—when bandwidth is insufficient,
 * the send queue grows, and latency grows with it without bound. So the congestion
 * signal is the fill level of that queue: `getItemsInCache()/getCacheSize()` in
 * RootEncoder. The fill ratio is the undelivered portion.
 *
 * **Where the numbers come from.** Recovery follows Larix's documented RTMP behavior:
 * a 500 kbps step no more often than every 30 seconds, and only if the queue stayed
 * empty the whole time. Reduction is proportional to the queue fill level (the same
 * source's equivalent of “reduce by the undelivered portion”), plus a sharp cut when
 * the queue is nearly full so latency has no time to grow.
 *
 * **The floor is [minimumBitrate] from the profile, not a fraction of the target.**
 * Larix's floor is 25%, but this project deliberately avoids reducing quality on the
 * user's behalf: the user sets the lower bound.
 *
 * **Thresholds are first approximations.** They are derived from queue behavior, not
 * measured in the field; calibration is a separate task.
 */
internal class QueueBitrateRegulator(
    private var targetBitrate: Long,
    private var minimumBitrate: Long,
    private var currentBitrate: Long = targetBitrate,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    /** Queue is nearly full: halve the bitrate without waiting for confirmation. */
    private val hardFill = 0.60

    /** Queue is significantly filled—reduce proportionally. */
    private val softFill = 0.20

    /** Queue is considered empty (a single frame in flight is normal). */
    private val clearFill = 0.05

    // “Never”: otherwise, at a zero clock value the first sharp reduction would be
    // blocked by the rate-limit window, precisely when congestion occurs at stream start.
    private var lastDecreaseAt = NEVER
    private var lastIncreaseAt = NEVER

    /** When the queue became continuously empty. 0 means it is not empty now. */
    private var clearSince = 0L

    /** A single queue spike is one keyframe, not congestion. Require the fill level to
     *  persist for two consecutive ticks, as `consecutiveBadMid` does in the SRT
     *  regulator, following the field finding from 31.08. */
    private var consecutiveSoft = 0

    @Synchronized
    fun configure(target: Long, min: Long) {
        targetBitrate = target
        minimumBitrate = min
        currentBitrate = currentBitrate.coerceIn(min, target)
    }

    @Synchronized
    fun reset(initial: Long = targetBitrate) {
        currentBitrate = initial.coerceIn(minimumBitrate, targetBitrate)
        lastDecreaseAt = NEVER
        lastIncreaseAt = NEVER
        clearSince = 0L
        consecutiveSoft = 0
    }

    @Synchronized
    fun current(): Long = currentBitrate

    /**
     * One regulator tick (called once per second).
     *
     * @return the new bitrate if it changed, otherwise null.
     */
    @Synchronized
    fun update(itemsInCache: Int, cacheSize: Int): Long? {
        if (cacheSize <= 0) return null
        val now = clock()
        val fill = itemsInCache.toDouble() / cacheSize.toDouble()
        var bitrate = currentBitrate

        if (fill <= clearFill) {
            if (clearSince == 0L) clearSince = now
        } else {
            clearSince = 0L
        }
        consecutiveSoft = if (fill >= softFill) consecutiveSoft + 1 else 0

        when {
            fill >= hardFill && now - lastDecreaseAt >= HARD_DECREASE_INTERVAL_MS -> {
                bitrate = maxOf(bitrate / 2, minimumBitrate)
                lastDecreaseAt = now
            }
            consecutiveSoft >= 2 && now - lastDecreaseAt >= SOFT_DECREASE_INTERVAL_MS -> {
                // Reduce by the portion the network did not take.
                bitrate = maxOf((bitrate * (1.0 - fill)).toLong(), minimumBitrate)
                lastDecreaseAt = now
            }
            clearSince != 0L &&
                now - clearSince >= RECOVERY_QUIET_MS &&
                now - lastIncreaseAt >= RECOVERY_INTERVAL_MS -> {
                bitrate = minOf(bitrate + RECOVERY_STEP_BPS, targetBitrate)
                lastIncreaseAt = now
            }
        }

        if (bitrate == currentBitrate) return null
        currentBitrate = bitrate
        return bitrate
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE / 2

        const val HARD_DECREASE_INTERVAL_MS = 3_000L
        const val SOFT_DECREASE_INTERVAL_MS = 2_000L

        /** Larix recovers RTMP bitrate every 30 s in 500 kbps steps. */
        const val RECOVERY_INTERVAL_MS = 30_000L
        const val RECOVERY_QUIET_MS = 30_000L
        const val RECOVERY_STEP_BPS = 500_000L
    }
}
