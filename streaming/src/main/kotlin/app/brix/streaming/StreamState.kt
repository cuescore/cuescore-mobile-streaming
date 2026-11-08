package app.brix.streaming

import app.brix.core.ConnectionStat
import app.brix.core.MicSource
import app.brix.core.StreamError
import app.brix.moblink.MoblinkRelayInfo

enum class StreamStatus {
    Idle,
    Connecting,
    Connected,
    Failed,
    Disconnected,
    Rejected,
}

enum class VideoEffect {
    NONE,
    GRAYSCALE,
    SEPIA,
}

data class StreamState(
    val status: StreamStatus = StreamStatus.Idle,
    val message: String? = null,
    val bitrateKbps: Long = 0,
    val throughput: String = "",
    val connections: List<ConnectionStat> = emptyList(),
    val moblinkRelays: List<MoblinkRelayInfo> = emptyList(),
    val adaptiveBitrateEnabled: Boolean = false,
    val adaptiveBitrateKbps: Long = 0,
    val micMuted: Boolean = false,
    /** Currently preferred microphone—for the label and button indicator. */
    val micSource: MicSource = MicSource.AUTO,
    val torchOn: Boolean = false,
    val blackScreenOn: Boolean = false,
    val videoEffect: VideoEffect = VideoEffect.NONE,
    val overlayVisible: Boolean = false,
    /** Whether at least one donation overlay's widget is currently connected
     *  to its alert server (per its own WS console logging) — null when no
     *  donation overlay is configured/no signal has arrived yet. */
    val donationWidgetConnected: Boolean? = null,
    /** State of each donation service: the ID from [app.brix.core.DonationService] →
     *  whether at least one of its widgets is connected. Empty when there are no donation widgets. */
    val donationServices: Map<String, Boolean> = emptyMap(),
    val phase: SessionPhase? = null,
    val error: StreamError? = null,
    val connectedAtElapsedMs: Long? = null,
    val reconnectAttempt: Int = 0,
)

/**
 * State after the live stream stops—shared by all three transports.
 *
 * **Why a separate function.** The reset was implemented three times, once in each streamer,
 * and they drifted apart: RTMP and WHIP cleared the link list, but SRTLA did not, so after Stop
 * the HUD showed frozen Wi-Fi and cellular bitrates as if the live stream were still running
 * (14.09). None of the three reset `adaptiveBitrateKbps`, even though that is what the HUD shows
 * when adaptive bitrate is enabled: they were resetting the wrong value.
 *
 * As long as the reset lives in three places, they will drift apart again. Here it is in one place.
 */
fun StreamState.stopped(): StreamState = copy(
    status = StreamStatus.Idle,
    message = null,
    bitrateKbps = 0,
    adaptiveBitrateKbps = 0,
    connections = emptyList(),
    error = null,
    connectedAtElapsedMs = null,
)
