package app.brix.streaming

import android.graphics.Bitmap
import android.graphics.Point
import android.view.SurfaceView
import android.view.View
import app.brix.core.AudioSettings
import app.brix.core.CameraDefaults
import app.brix.core.CameraSide
import app.brix.core.MicSource
import app.brix.core.MoblinkSettings
import app.brix.core.SceneAudio
import app.brix.core.StreamProfile
import app.brix.streaming.overlay.DonationAudioPlayer
import app.brix.streaming.overlay.OverlayFrame
import app.brix.streaming.overlay.OverlaySize
import kotlinx.coroutines.flow.StateFlow

interface LiveStreamer {
    val state: StateFlow<StreamState>

    /**
     * An `elapsedRealtime` timestamp once per second — only for the stream
     * uptime counter in the HUD.
     *
     * **Why a separate flow instead of a [StreamState] field.** This used to be
     * the `statsTickElapsedMs` field, changing the overall state every second.
     * Compose tracks reads at the state-object level, and the streamer screen
     * collects `state` as a whole at the very top — so a timestamp nobody cares
     * about except the clock invalidated the entire composable body, over two
     * thousand lines long: button lists were rebuilt, handlers recreated, and
     * derived values recalculated. Every second, for the whole broadcast, just
     * to change `00:29` to `00:30`.
     *
     * The separate flow is read only by the part of the UI that displays the
     * clock, so only that part redraws. No second timer is created: the same
     * loops already running publish the tick (`StatsReporter` in SRTLA,
     * `UptimeTicker` in RTMP and WHIP).
     */
    val uptimeTick: StateFlow<Long>

    /** Microphone level per channel, 0..1 — for the on-screen indicator.
      *  Emitted on a separate flow, like the clock tick: updated dozens of times per second
      *  and must not trigger a redraw of the whole screen. */
    val audioLevelLeft: StateFlow<Float>
    val audioLevelRight: StateFlow<Float>

    /** Input is overloaded: the peak hit the ceiling. */
    val audioClipping: StateFlow<Boolean>
    val isStreaming: Boolean

    fun prepare(): Boolean
    fun startPreview(surfaceView: SurfaceView)
    fun stopPreview()
    fun configure(profile: StreamProfile, srtLatencyMs: Int = 2000)
    /** Apply Moblink (relay bonding) settings. No-op for transports other than
     *  SRTLA — Moblink relays only make sense as extra SRTLA bonding channels. */
    fun configureMoblink(settings: MoblinkSettings) = Unit
    /** Apply camera defaults (default lens, torch-on-start, stabilization,
     *  mirror front preview, tap-to-focus). Default no-op — only SrtlaStreamer
     *  implements this today. */
    fun configureCamera(defaults: CameraDefaults) = Unit
    /** Shared audio settings. A separate call rather than part of the profile:
     *  since 03.09, audio settings are app-wide, not duplicated in every profile. */
    fun configureAudio(audio: AudioSettings) = Unit
    /** Whether to record the live stream to MP4 alongside broadcasting. No-op
     *  by default — until 03.09, the "Record stream" settings toggle did nothing. */
    fun setRecordStream(enabled: Boolean) = Unit
    /** Whether to write a CSV session log (signal strength, temperature,
     *  transport) to the phone. Implemented only for SRTLA: the log uses the
     *  same metrics as `BrixStat`, which are available only on this path. */
    fun setDebugLog(enabled: Boolean) = Unit
    /** Switch the preferred microphone on the fly (quick button). */
    fun setMicSource(source: MicSource, deviceName: String = "") = Unit
    fun start(url: String)
    fun stop()
    fun reconnect()
    fun switchCamera()
    /** Switch to a specific camera side (needed by scenes). */
    fun setCameraSide(side: CameraSide) = Unit
    /** Still image instead of the camera. @return false if the image cannot be read. */
    fun showStillImage(uri: android.net.Uri): Boolean = false
    /** Phone screen instead of the camera. @return false if capture did not start. */
    fun showScreen(projection: android.media.projection.MediaProjection): Boolean = false
    /** Restore the camera after the still image. */
    fun showCamera() = Unit
    /**
     * Where to get audio for this scene.
     *
     * [projection] is needed for everything except [SceneAudio.MIC], and is the
     * same token used for screen capture. @return false if switching failed —
     * the caller must stay on the microphone rather than switch to silence.
     */
    fun setSceneAudio(
        mode: SceneAudio,
        projection: android.media.projection.MediaProjection?,
    ): Boolean = false
    fun setAdaptiveBitrate(enabled: Boolean)
    /** Live-update ABR target/minimum when the profile changes mid-stream. */
    fun updateAdaptiveBitrateLimits(targetKbps: Int, minKbps: Int, initialKbps: Int)
    fun setVideoBitrate(bitrate: Int)
    fun setMuted(muted: Boolean)
    fun setTorch(enabled: Boolean)
    fun setBlackScreen(enabled: Boolean)
    /** Apply a whole-frame color effect (grayscale, sepia, ...) via a native
     *  GL filter — reuses RootEncoder's own built-in filter renders, not a
     *  custom shader. [VideoEffect.NONE] removes any active effect filter. */
    fun setVideoEffect(effect: VideoEffect)
    /** Enable/disable [overlayId]'s contribution to the stream's donation-audio
     *  mix (several overlays can be armed at once — their chunks are summed).
     *  [donationPlayer] is the source of donation PCM chunks; required when
     *  [enabled] is true. */
    fun setOverlayAudioInStream(overlayId: String, enabled: Boolean, donationPlayer: DonationAudioPlayer?)
    /** Play a native in-stream overlay (GIF frames) for [overlayId] on the GL
     *  pipeline. [posX]/[posY] are the box's center as fractions (0..1) of the
     *  video frame. Each overlay id renders independently. */
    fun showOverlay(overlayId: String, frames: List<OverlayFrame>, posX: Float, posY: Float, size: OverlaySize, durationMs: Long)
    /** Hide/clear the native in-stream overlay for [overlayId]. */
    fun hideOverlay(overlayId: String)
    /** Current encoder video frame size in px, or null if not yet known (e.g.
     *  no stream/preview prepared yet) — used to convert a bitmap's own pixel
     *  aspect ratio into a correct on-frame size fraction (the frame usually
     *  isn't square, so width-fraction and height-fraction aren't the same
     *  physical scale). */
    fun videoFrameSize(): Point?

    /** Snapshot of the current frame as seen by the viewer, including overlays
     *  and effects. Captured from the GL pipeline, not the camera, so this is
     *  the actual stream frame, not a separate photo. [onResult] is called on
     *  the GL thread; null if the frame could not be captured. */
    fun takeSnapshot(onResult: (android.graphics.Bitmap?) -> Unit)
    /** Report whether a donation overlay's widget is currently connected to
     *  its alert server, aggregated (any-connected) into [StreamState.donationWidgetConnected]. */
    fun setOverlayConnectionState(overlayId: String, serviceId: String, connected: Boolean)

    /** The widget is no longer on screen: remove its HUD indicator entirely,
     *  otherwise it stays dimmed until the app restarts. */
    fun forgetOverlayConnection(overlayId: String)
    /** Show a persistent, manually-updated overlay (e.g. a live browser
     *  widget snapshot) — unlike [showOverlay] this has no built-in
     *  duration/fade; it stays until [detachLiveOverlay]. */
    fun attachLiveOverlay(overlayId: String, posX: Float, posY: Float, size: OverlaySize)
    /** Push a new frame to an overlay attached via [attachLiveOverlay]. */
    fun updateLiveOverlayFrame(overlayId: String, bitmap: Bitmap)
    /** Remove an overlay attached via [attachLiveOverlay]. */
    fun detachLiveOverlay(overlayId: String)
    /** Switch to a specific rear lens (see Context.rearLenses()); zoom>1
     *  applies a zoom ratio after opening (virtual tele). False if unavailable. */
    fun setLens(cameraId: String, zoom: Float = 1f): Boolean
    /** Current rear lens id, null when unknown or front camera is active. */
    fun currentLensId(): String?
    fun zoomByScale(scale: Float)
    fun getZoomRange(): ClosedFloatingPointRange<Float>?
    fun tapFocus(view: View, x: Float, y: Float)
    val isReleased: Boolean

    fun release()
}
