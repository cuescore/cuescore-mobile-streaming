package app.brix.streaming

/**
 * SRTLA reconnection decisions: when to move between session phases, when to
 * take the next backoff step, and when to give up.
 *
 * **Why a separate class.** This logic lived inside SrtlaStreamer, mixed in with
 * the camera, encoder, and Android callbacks, and could only be tested on-air.
 * The 23.09 audit found three serious bugs at once: manual reconnection left the
 * session stuck in Reconnecting, failures consumed extra steps (only three of
 * five attempts remained), and late success hit a forbidden transition. There is
 * no Android or coroutines here — only [StreamSession], [ReconnectPolicy], and
 * two interfaces, so each scenario can be tested with a regular unit test
 * (SrtlaReconnectControllerTest).
 *
 * The streamer is still responsible for threads and for checking event ownership
 * (`session.matches(activeGen)`): events arrive here already filtered.
 */
internal class SrtlaReconnectController(
    private val session: StreamSession,
    private val host: Host,
    private val scheduler: Scheduler,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
) {
    /** What the controller asks the streamer to do. */
    interface Host {
        /** Move phase and update stream state (`updateSession`). */
        fun moveTo(phase: SessionPhase, gen: Long): Boolean

        /** Bring the transport back up; false means there is no address yet. */
        fun reconnectTransport()

        /** Manual reconnect from Failed: start a new session and move to Connecting. */
        fun restartFromFailed(): Boolean

        /** Attempt number for the HUD and notification. */
        fun onAttempt(attempt: Int)

        /** Attempts exhausted: set the error state and stop the pipeline. */
        fun onExhausted(gen: Long)

        /** Failure outside reconnection: [retryable] decides whether to keep the pipeline. */
        fun onFatal(reason: String)
    }

    fun interface Scheduler {
        fun after(delayMs: Long, block: () -> Unit): Handle
    }

    interface Handle {
        val isActive: Boolean
        fun cancel()
    }

    private var pending: Handle? = null

    /** Current attempt is a reconnect, not the initial start. This determines
     *  whether failure means retry or transition to Failed. */
    @Volatile
    var isReconnectAttempt = false
        private set

    val attempts: Int get() = policy.attempts

    /** New stream: reset the counter and cancel anything scheduled. */
    fun onStarted() {
        cancelPending()
        policy.reset()
        isReconnectAttempt = false
        host.onAttempt(0)
    }

    /** Stop or release. */
    fun onStopped() {
        cancelPending()
        isReconnectAttempt = false
    }

    /**
     * The “Reconnect” button (screen or notification).
     *
     * From Live and Reconnecting, move to Connecting — as scheduleReconnect and
     * RTMP/WHIP do. Previously the phase stayed Reconnecting, but the transition
     * Reconnecting -> Live is forbidden by the table: the stream stayed dimmed,
     * the clock stopped, and the next real disconnect no longer triggered auto-reconnect.
     */
    fun manualReconnect() {
        cancelPending()
        policy.reset()
        host.onAttempt(0)
        isReconnectAttempt = true
        val gen = session.generation
        when (session.phase) {
            SessionPhase.Live -> {
                if (!host.moveTo(SessionPhase.Reconnecting, gen)) return
                if (!host.moveTo(SessionPhase.Connecting, gen)) return
            }
            SessionPhase.Reconnecting -> if (!host.moveTo(SessionPhase.Connecting, gen)) return
            SessionPhase.Preparing,
            SessionPhase.Connecting,
            -> Unit
            SessionPhase.Failed -> if (!host.restartFromFailed()) return
            else -> return
        }
        host.reconnectTransport()
    }

    /**
     * The transport came up. Guard against late success: if the session thought
     * it was Reconnecting (the handshake response arrived after a disconnect was
     * declared), move through Connecting — otherwise the stream is live while the
     * session remains stuck “reconnecting.”
     */
    fun onSuccess() {
        policy.reset()
        host.onAttempt(0)
        isReconnectAttempt = false
        cancelPending()
        val gen = session.generation
        if (session.phase == SessionPhase.Reconnecting) host.moveTo(SessionPhase.Connecting, gen)
        host.moveTo(SessionPhase.Live, gen)
    }

    /**
     * Transport failure. During reconnection, consume a backoff step only if the
     * phase actually changed or no attempt is scheduled yet: handshake timeout,
     * connection timeout, and disconnect each report one failure, and previously
     * each one consumed a step.
     */
    fun onFailure(reason: String) {
        if (session.phase == SessionPhase.Failed) return
        if (isReconnectAttempt) {
            val moved = host.moveTo(SessionPhase.Reconnecting, session.generation)
            if (moved || (session.phase == SessionPhase.Reconnecting && pending?.isActive != true)) {
                schedule()
            }
            return
        }
        host.onFatal(reason)
    }

    /** Disconnect from a live or connecting stream. */
    fun onDisconnect() {
        if (session.phase == SessionPhase.Live || session.phase == SessionPhase.Connecting) {
            if (host.moveTo(SessionPhase.Reconnecting, session.generation)) schedule()
        }
    }

    private fun schedule() {
        cancelPending()
        val gen = session.generation
        val delayMs = policy.nextDelayMs()
        host.onAttempt(policy.attempts)
        if (delayMs == null) {
            host.onExhausted(gen)
            return
        }
        pending = scheduler.after(delayMs) {
            if (host.moveTo(SessionPhase.Connecting, gen)) {
                isReconnectAttempt = true
                if (session.matches(gen)) host.reconnectTransport()
            }
        }
    }

    private fun cancelPending() {
        pending?.cancel()
        pending = null
    }
}
