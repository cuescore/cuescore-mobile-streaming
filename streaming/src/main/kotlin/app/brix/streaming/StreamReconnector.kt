package app.brix.streaming

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reconnection ladder: waits for the next delay from [ReconnectPolicy], then
 * calls [onAttempt]. When the steps run out, calls [onExhausted].
 *
 * Extracted from `SrtlaStreamer.scheduleReconnect` for more than just tidiness.
 * Until Sep 3, RTMP and WHIP didn't reconnect at all: `onDisconnect` immediately
 * moved the session to `Failed`, while their `reconnect()` required the `Live` or
 * `Connecting` phase and therefore couldn't recover from `Failed`—the notification's
 * "Reconnect" button did nothing either. The only fix was to manually Stop→Start.
 * Copying the same code here a fourth time would have created a fourth place to fix it.
 *
 * This is a separate class also so the ladder can finally be tested: `scope` is
 * supplied from outside, and tests use `TestScope` with virtual time—no network,
 * phone, or thirty-second `Thread.sleep`.
 *
 * Thread safety: [schedule], [reset], and [cancel] are called from both transport
 * callbacks and the UI, so access to [job] is guarded by a monitor. [ReconnectPolicy]
 * is already synchronized internally.
 */
internal class StreamReconnector(
    private val scope: CoroutineScope,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    /** Number of the attempt that started — for `StreamState.reconnectAttempt` in the HUD. */
    private val onScheduled: (attempt: Int) -> Unit = {},
    /** Retry stages exhausted: the caller decides what to do next (usually, transition to Failed). */
    private val onExhausted: () -> Unit = {},
    private val onAttempt: suspend () -> Unit,
) {
    private val tag = "BrixStream"

    private var job: Job? = null

    val attempts: Int get() = policy.attempts

    /**
     * Schedule the next attempt. Any previously scheduled attempt is canceled:
     * on disconnect, transport callbacks arrive in a burst, and without this one
     * failure would consume several ladder steps at once.
     */
    fun schedule() {
        // Call callbacks OUTSIDE the monitor: onExhausted changes the session
        // phase in the streamer, and holding this lock while doing so would repeat
        // the exact pattern that led us to move a callback outside AdaptiveBitrate's
        // lock on Sep 2.
        val delayMs = takeNextStep()
        if (delayMs == null) {
            Log.w(tag, "reconnect: ступени кончились после ${policy.attempts} попыток")
            onExhausted()
            return
        }
        val attempt = policy.attempts
        Log.w(tag, "reconnect: попытка $attempt через ${delayMs}ms")
        onScheduled(attempt)
        launchAttempt(delayMs)
    }

    @Synchronized
    private fun takeNextStep(): Long? {
        job?.cancel()
        job = null
        return policy.nextDelayMs()
    }

    @Synchronized
    private fun launchAttempt(delayMs: Long) {
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            onAttempt()
        }
    }

    /** Successfully connected—the ladder starts over. */
    @Synchronized
    fun reset() {
        job?.cancel()
        job = null
        policy.reset()
    }

    /** Stop or release: a scheduled attempt must not bring the live stream back. */
    @Synchronized
    fun cancel() {
        job?.cancel()
        job = null
    }
}
