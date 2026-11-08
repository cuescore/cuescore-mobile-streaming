package app.brix.streaming

/**
 * Reconnect backoff policy: 1 -> 2 -> 5 -> 10 -> 30 seconds,
 * a bounded number of attempts, after which the session transitions to Failed.
 *
 * The delay list is finite: once [exhausted], no further attempt is scheduled.
 * Call [reset] (e.g. on a successful connection) to start over.
 */
class ReconnectPolicy(
    private val delaysMs: List<Long> = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L),
) {
    // Three threads touch this counter: start()/reconnect() under SrtlaStreamer's
    // monitor, onConnectionSuccess() from a transport callback without a monitor,
    // and nextDelayMs() from scheduleReconnect's IO coroutine. No shared lock,
    // while `attempt++` is read-modify-write, causing missed or duplicate
    // backoff steps and divergence from StreamState.reconnectAttempt.
    // This tiny class is not on a hot path, so use a monitor for everything.
    private var attempt = 0

    @get:Synchronized
    val attempts: Int get() = attempt

    @get:Synchronized
    val exhausted: Boolean get() = attempt >= delaysMs.size

    /** @return the next backoff delay in ms, or null when the policy is exhausted. */
    @Synchronized
    fun nextDelayMs(): Long? {
        if (attempt >= delaysMs.size) return null
        return delaysMs[attempt++]
    }

    /** Start over after a successful (re)connect. */
    @Synchronized
    fun reset() {
        attempt = 0
    }

    /** True if there is at least one more retry available. */
    @Synchronized
    fun hasNext(): Boolean = attempt < delaysMs.size
}