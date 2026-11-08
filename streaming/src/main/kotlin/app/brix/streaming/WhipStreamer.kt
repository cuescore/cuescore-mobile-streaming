package app.brix.streaming

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import android.view.View
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.utils.CodecUtil
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.input.gl.render.filters.BlackFilterRender
import com.pedro.encoder.input.gl.render.filters.GreyScaleFilterRender
import com.pedro.encoder.input.gl.render.filters.SepiaFilterRender
import com.pedro.library.whip.WhipStream
import app.brix.core.AudioSettings
import app.brix.core.ConnectionStat
import app.brix.core.CameraDefaults
import app.brix.core.CameraSide
import app.brix.core.MicSource
import app.brix.core.SceneAudio
import app.brix.core.Codec
import app.brix.core.StreamError
import app.brix.core.StreamErrorCode
import app.brix.core.StreamProfile
import app.brix.core.VideoSettings
import app.brix.streaming.overlay.OverlayFrame
import app.brix.streaming.overlay.OverlaySize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * WHIP (WebRTC-HTTP Ingestion Protocol) output — mirrors [Streamer] (RTMP)
 * structurally, on RootEncoder's [WhipStream] instead of `RtmpStream`. Both
 * extend the same `StreamBase`, so nearly the whole surface (prepare/preview/
 * camera/overlay no-ops) is identical; WHIP forces Opus audio internally
 * (`WhipStream`'s own init), which is compatible with our default 48kHz
 * `AudioSettings` without any change here.
 */
class WhipStreamer(context: Context) : ConnectChecker, LiveStreamer {

    private val appContext = context.applicationContext
    private val tag = "BrixStream"
    private val _state = MutableStateFlow(StreamState())
    override val state: StateFlow<StreamState> = _state.asStateFlow()

    // The live-stream clock tick is separate from state, so the per-second timestamp
    // does not redraw the entire streamer screen. It is published from the same
    // loop that is already running; no second timer is created.
    private val _uptimeTick = MutableStateFlow(0L)
    override val uptimeTick: StateFlow<Long> = _uptimeTick.asStateFlow()

    private val micMeter = MicLevelMeter()
    override val audioLevelLeft: StateFlow<Float> = micMeter.left
    override val audioLevelRight: StateFlow<Float> = micMeter.right
    override val audioClipping: StateFlow<Boolean> = micMeter.clipping

    private val stream = WhipStream(appContext, this).apply {
        getGlInterface().autoHandleOrientation = true
    }

    private var profile: StreamProfile? = null
    private val session = StreamSession()
    private var currentUrl: String? = null
    private var activeGen = 0L

    /*The encoder has already been prepared by this prepare(). Without this flag,
     *  a second prepare() during a live preview would always fail — see [prepare]. */
    /** Accumulated bytes for the single channel line in the HUD. */
    @Volatile
    private var sentBytes = 0L

    @Volatile
    private var prepared = false

    /** Codec baked into the encoder by the most recent prepare(): the bitrate can change
      *  on the fly, but the codec cannot; changing it requires rebuilding. */
    @Volatile
    private var appliedCodec: Codec? = null

    /** Keyframe interval baked into the most recent prepare(). Like the codec,
      *  it cannot change on the fly, so changing it also requires rebuilding —
      *  otherwise the setting would silently have no effect until the app restarts. */
    @Volatile
    private var appliedKeyframeSec: Int? = null

    private val opsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // See the same comment in [Streamer]: the camera is shared by three transports,
    // but until 03.09 it was only fixed in the SRTLA path.
    private val camera = CameraController(
        appContext = appContext,
        stream = stream,
        scope = opsScope,
        profileFps = { profile?.video?.fps ?: 30 },
        isReleased = { isReleased },
        updateState = { transform -> _state.update(transform) },
    )

    // Otherwise there is no way to distinguish “could not connect on the first try”
    // from “disconnected mid-stream”: the transport calls onConnectionFailed in both cases.
    // The first is an error for the user; the second is a reason to continue the retry ladder.
    @Volatile
    private var isReconnectAttempt = false

    private val reconnector = StreamReconnector(
        scope = opsScope,
        onScheduled = { attempt -> _state.update { it.copy(reconnectAttempt = attempt) } },
        onExhausted = {
            if (updateSession(SessionPhase.Failed)) {
                _state.update {
                    it.copy(
                        error = StreamError(
                            code = StreamErrorCode.NETWORK_UNAVAILABLE,
                            message = "Reconnect attempts exhausted",
                            retryable = true,
                        ),
                    )
                }
            }
            runCatching { if (stream.isStreaming) stream.stopStream() }
        },
    ) { attemptReconnect() }

    private val uptime = UptimeTicker(
        scope = opsScope,
        publishTick = { tick -> _uptimeTick.value = tick },
    )

    private val overlays = OverlayHost(
        stream = stream,
        micMuted = { _state.value.micMuted },
        micGain = { audioSettings.micGain },
        micMeter = micMeter,
        scope = opsScope,
        isReleased = { isReleased },
        updateState = { transform -> _state.update(transform) },
    )

    /**
     * One retry-ladder attempt. Unlike SRT, there is no transport-level reconnection here:
     * for WHIP this requires a full stream restart.
     */
    @Synchronized
    private fun attemptReconnect() {
        val url = currentUrl ?: return
        if (!session.matches(activeGen)) return
        if (!updateSession(SessionPhase.Connecting)) return
        isReconnectAttempt = true
        runCatching { stream.stopStream() }
        stream.startStream(url)
        // stopStream() above stops camera capture; otherwise the live stream would resume with
        // a frozen image.
        camera.resumePreviewIfDetached()
    }

    override val isStreaming: Boolean get() = stream.isStreaming

    override fun configure(profile: StreamProfile, srtLatencyMs: Int) {
        this.profile = profile
        // We do NOT touch adaptiveBitrateEnabled here: it is a runtime toggle controlled by
        // setAdaptiveBitrate(). Reloading the saved profile reset the manual selection on every
        // press of “Start” — the toggle would turn itself on when the live stream started.
        // SrtlaStreamer has explicitly prohibited this from the start; that change did not
        // make it here.
        // The queue-depth controller is deliberately NOT connected here.
        // It assumes “TCP does not lose packets, and the send queue grows when bandwidth
        // is insufficient” — true for RTMP. WHIP is WebRTC:
        // signaling uses HTTP, media uses UDP/SRTP. Packets can be lost, while the queue
        // may not grow at all because the socket is not backlogged. In other words, the
        // controller would sit at the target bitrate and never reduce it, merely giving the
        // illusion of working ABR.
        // WHIP needs a separately chosen signal; RootEncoder has its own congestion indicator
        // `StreamBaseClient.hasCongestion(float)`.
        //
    }

    override fun prepare(): Boolean {
        // `StreamBase.prepareVideo` throws IllegalStateException while a stream, recording, OR
        // PREVIEW is active. But the UI calls prepare() twice: once when connecting the preview
        // (which is not active yet, so it succeeds), and again when the user presses “Start” —
        // with the preview now live. In the SRTLA path, an early return on `prepared` and
        // stopping the preview during reconfiguration prevent this; those safeguards, like the
        // other fixes, never made it here, so RTMP/WHIP startup ALWAYS failed with
        // “Failed to prepare stream”.
        // This is not new: the same failure was caught in the field on 31.08 while changing codecs.
        val wantCodec = profile?.video?.codec ?: VideoSettings().codec
        val wantKeyframe = profile?.video?.keyframeIntervalSec ?: VideoSettings().keyframeIntervalSec
        if (prepared && appliedCodec == wantCodec && appliedKeyframeSec == wantKeyframe) return true
        if (prepared && stream.isStreaming) {
            Log.w(tag, "prepare: смена кодека $appliedCodec -> $wantCodec отложена до остановки эфира")
            return true
        }
        val restorePreview = stream.isOnPreview
        runCatching { if (stream.isOnPreview) stream.stopPreview() }
        prepared = false
        return try {
            val p = profile?.video ?: VideoSettings()
            val a = audioSettings
            // See SrtlaStreamer.prepare() — force hardware for video only;
            // forcing it for audio too breaks prepare on devices with no
            // hardware AAC encoder (confirmed on a real device).
            stream.forceCodecType(CodecUtil.CodecType.HARDWARE, CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND)
            stream.setVideoCodec(if (p.codec == Codec.HEVC) VideoCodec.H265 else VideoCodec.H264)
            val videoOk = stream.prepareVideo(
                width = p.width,
                height = p.height,
                bitrate = p.bitrateKbps * 1000,
                fps = p.fps,
                // The keyframeIntervalSec field has been in the model from the start but was never
                // read: it was written to disk and did nothing. The keyframe interval matters to viewers —
                // it determines how quickly video appears on connection and after
                // loss — so pass it to the encoder instead of discarding it.
                // loss — so we pass it on to the encoder instead of discarding it.
                iFrameInterval = p.keyframeIntervalSec,
                // Hardware-capability profile — see SrtlaStreamer.prepare().
                profile = EncoderCapabilities.bestProfile(p.codec),
            )
            ensureMeteredAudioSource()
            val audioOk = stream.prepareAudio(
                sampleRate = a.sampleRate,
                isStereo = a.stereo,
                bitrate = a.bitrateKbps * 1000,
            )
            prepared = videoOk && audioOk
            if (prepared) appliedCodec = p.codec
            appliedKeyframeSec = p.keyframeIntervalSec
            if (restorePreview) {
                // The preview was stopped for reconfiguration — restore it, or the screen will freeze
                // on the last frame and the user will think the camera has died.
                camera.resumePreviewIfDetached()
            }
            if (!videoOk || !audioOk) {
                _state.update {
                    it.copy(
                        status = StreamStatus.Failed,
                        phase = null,
                        error = StreamError(
                            code = if (!videoOk) StreamErrorCode.CAMERA_OPEN_FAILED
                            else StreamErrorCode.ENCODER_PREPARE_FAILED,
                            message = if (!videoOk) "Failed to open camera" else "Failed to prepare encoder",
                            retryable = true,
                        ),
                    )
                }
                return false
            }
            true
        } catch (e: SecurityException) {
            Log.e(tag, "prepare failed: нет разрешения камеры", e)
            _state.update {
                it.copy(
                    status = StreamStatus.Failed,
                    phase = null,
                    error = StreamError(
                        code = StreamErrorCode.CAMERA_PERMISSION_DENIED,
                        message = "Camera permission denied",
                        technical = e.message,
                    ),
                )
            }
            false
        } catch (e: Exception) {
            // Previously, the exception text went ONLY to StreamState.technical, which means
            // nothing was logged: on-device, “Failed to prepare
            // stream” appeared with complete silence in logcat, leaving nothing to investigate.
            Log.e(tag, "prepare failed", e)
            _state.update {
                it.copy(
                    status = StreamStatus.Failed,
                    phase = null,
                    error = StreamError(
                        code = StreamErrorCode.ENCODER_PREPARE_FAILED,
                        message = "Failed to prepare stream",
                        technical = e.message,
                    ),
                )
            }
            false
        }
    }

    override fun startPreview(surfaceView: SurfaceView) = camera.startPreview(surfaceView)

    override fun stopPreview() = camera.stopPreview()

    override fun configureCamera(defaults: CameraDefaults) = camera.configure(defaults)

    @Volatile
    private var audioSettings = AudioSettings()

    override fun configureAudio(audio: AudioSettings) {
        audioSettings = audio
        micSourceOrNull()?.let { mic ->
            mic.microphoneVolume = audio.micGain
            mic.audioSource = MicDevices.audioSourceOf(audio.processing)
        }
        (stream.audioSource as? com.pedro.encoder.input.sources.audio.MixAudioSource)?.microphoneVolume = audio.micGain
        preferredMic = audio.micSource
        preferredMicName = audio.micDeviceName
        _state.update { it.copy(micSource = audio.micSource) }
        applyMicSource()
    }


    /*Microphone for the current source: since 17.09 even a clean microphone is wrapped in
     *  [OverlayAudioSource] — it measures the level for the HUD indicator. All
     *  microphone settings apply to the wrapped source. */
    private fun micSourceOrNull(): MicrophoneSource? = when (val source = stream.audioSource) {
        is MicrophoneSource -> source
        is app.brix.streaming.overlay.OverlayAudioSource -> source.micSourceForPreference()
        else -> null
    }

    /*Install our source if the library’s bare microphone is selected. */
    private fun ensureMeteredAudioSource() {
        // Only the library’s bare microphone. Any other source was selected by the scene
        // (for example, internal audio during screen capture), and must not be replaced
        // with the microphone when rebuilding the stream.
        if (stream.audioSource !is MicrophoneSource) return
        runCatching {
            stream.changeAudioSource(app.brix.streaming.overlay.OverlayAudioSource(meter = micMeter))
            configureAudio(audioSettings)
            setMuted(_state.value.micMuted)
        }.onFailure { Log.w(tag, "источник звука с измерителем не встал: ${it.message}") }
    }

    private val recorder = StreamRecorder(appContext, stream)

    override fun setRecordStream(enabled: Boolean) {
        recorder.enabled = enabled
    }

    @Volatile
    private var preferredMic = MicSource.AUTO
    private var preferredMicName = ""

    override fun setMicSource(source: MicSource, deviceName: String) {
        preferredMic = source
        preferredMicName = deviceName
        _state.update { it.copy(micSource = source) }
        applyMicSource()
    }

    /*The preference is reset together with the audio source, so this is called both
     *  from configure() and when toggling the button. */
    private fun applyMicSource() {
        val mic = micSourceOrNull() ?: return
        // The HAL may refuse, and that refusal must be visible; otherwise the “from camera”
        // selection silently remains “system”.
        val applied = runCatching {
            mic.setPreferredDevice(MicDevices.deviceFor(appContext, preferredMic, preferredMicName))
        }.getOrDefault(false)
        if (!applied) {
            Log.w("BrixMic", "устройство не принято HAL: $preferredMic — звук с микрофона по выбору системы")
        }
    }

    // One monitor for the entire lifecycle — exactly what the comment on
    // release() promised from the start, but the code did not provide: the released flag
    // was checked without it, so ACTION_START from a notification could call
    // start() concurrently with release() and bring up a live stream on an already-released
    // stream. SrtlaStreamer has had this protection since the original audit (P0-2).
    @Synchronized
    override fun start(url: String) {
        if (stream.isStreaming) return
        val gen = session.start() ?: return
        activeGen = gen
        currentUrl = url
        reconnector.reset()
        isReconnectAttempt = false
        sentBytes = 0L
        // Take the initial bitrate from ABR settings, not the video profile:
        // initialBitrateKbps exists precisely to start more cautiously than the target
        // and ramp up as the network proves itself.
        _state.update {
            it.copy(status = StreamStatus.Connecting, phase = session.phase, error = null, reconnectAttempt = 0)
        }
        stream.startStream(url)
        uptime.start()
        recorder.start()
        // See Streamer.start(): stopStream() stops camera capture, and without this
        // the preview freezes on the last frame after Stop->Start.
        camera.resumePreviewIfDetached()
        camera.applyTorchOnStart()
    }

    @Synchronized
    override fun stop() {
        val gen = session.generation
        activeGen = 0
        reconnector.cancel()
        isReconnectAttempt = false
        uptime.stop()
        recorder.stop()
        if (stream.isStreaming) stream.stopStream()
        updateSession(SessionPhase.Stopping, gen)
        updateSession(SessionPhase.Released, gen)
        _state.update { it.stopped() }
    }

    @Synchronized
    override fun reconnect() {
        // The guard used to require the Live/Connecting phase, but callbacks arrive here
        // from Failed — the “Reconnect” button in the notification after a disconnect
        // did nothing at all, so recovery required a manual Stop→Start.
        // The Failed→Reconnecting transition is FORBIDDEN by the table (Failed only leads to
        // Idle/Released), so we recover from failure through session.start(),
        // as SrtlaStreamer does.
        val url = currentUrl ?: return
        // A manual press explicitly means “try now”, so restart the ladder
        // instead of continuing from thirty seconds.
        reconnector.reset()
        _state.update { it.copy(reconnectAttempt = 0) }
        when (session.phase) {
            SessionPhase.Live -> if (!updateSession(SessionPhase.Reconnecting)) return
            SessionPhase.Failed -> {
                session.start() ?: return
                activeGen = session.generation
            }
            SessionPhase.Preparing, SessionPhase.Connecting, SessionPhase.Reconnecting -> Unit
            else -> return
        }
        if (session.phase != SessionPhase.Connecting && !updateSession(SessionPhase.Connecting)) return
        isReconnectAttempt = true
        runCatching { stream.stopStream() }
        _state.update { it.copy(status = StreamStatus.Connecting, phase = session.phase, error = null) }
        stream.startStream(url)
        camera.resumePreviewIfDetached()
    }

    override fun switchCamera() = camera.switchCamera()

    override fun setCameraSide(side: CameraSide) = camera.setCameraSide(side)

    override fun showStillImage(uri: android.net.Uri): Boolean = camera.showStillImage(uri)

    override fun showScreen(projection: android.media.projection.MediaProjection): Boolean =
        camera.showScreen(projection)

    override fun showCamera() = camera.showCamera()

    /**
     * Scene audio: microphone, phone audio, or both.
     *
     * Be careful with overlays: when donation sounds are enabled, our mixer
     * [OverlayAudioSource] is the audio source, and replacing it here
     * removes it — donation sounds will be silent on the live stream until the next toggle.
     * This is a known limitation, not an accident: both mechanisms compete for
     * the same audio-source slot.
     */
    override fun setSceneAudio(
        mode: SceneAudio,
        projection: android.media.projection.MediaProjection?,
    ): Boolean {
        val source = when (mode) {
            // Use our own source even for a clean microphone: it also measures the level
            // for the HUD indicator (MicLevelMeter), while mixing is disabled.
            SceneAudio.MIC -> app.brix.streaming.overlay.OverlayAudioSource(meter = micMeter)
            SceneAudio.INTERNAL -> {
                val p = projection ?: return false
                com.pedro.encoder.input.sources.audio.InternalAudioSource(p, null)
            }
            SceneAudio.BOTH -> {
                val p = projection ?: return false
                com.pedro.encoder.input.sources.audio.MixAudioSource(p, null, 0)
            }
        }
        return runCatching {
            stream.changeAudioSource(source)
            // Mute, gain, and device selection reset together with the
            // source — confirm them again, as everywhere else. Mute was not confirmed
            // here at all before: changing scenes unmuted the microphone.
            configureAudio(audioSettings)
            setMuted(_state.value.micMuted)
            true
        }.getOrElse {
            Log.e(tag, "звук сцены не переключён: ${it.message}")
            false
        }
    }


    override fun setAdaptiveBitrate(enabled: Boolean) {
        // WHIP has no controller (see updateAdaptiveBitrateLimits), so
        // we do not set the “enabled” indicator either. Previously the button lit up and
        // promised congestion protection that did not exist (audit 23.09).
        if (enabled) Log.w(tag, "ABR на WHIP не поддержан — кнопка не включается")
        _state.update { it.copy(adaptiveBitrateEnabled = false) }
    }

    override fun updateAdaptiveBitrateLimits(targetKbps: Int, minKbps: Int, initialKbps: Int) {
        // Intentionally empty: adaptive bitrate is not available on WHIP yet; see the comment
        // in configure(). A stub is more honest than a controller that cannot see congestion.
    }

    override fun setMuted(muted: Boolean) {
        _state.update { it.copy(micMuted = muted) }
        // Our source mutes itself after measuring the level (the indicator
        // shows the voice in gray while muted); the bare microphone uses the library’s mute.
        when (val source = stream.audioSource) {
            is app.brix.streaming.overlay.OverlayAudioSource -> source.setMicMuted(muted)
            is MicrophoneSource -> if (muted) source.mute() else source.unMute()
        }
        // For the “microphone + phone audio” scene, mute only the microphone part; phone audio
        // continues. Previously this source was missed, so the button showed “off”
        // while the voice was still broadcast (audit 23.09).
        (stream.audioSource as? com.pedro.encoder.input.sources.audio.MixAudioSource)?.let { mix ->
            if (muted) mix.mute() else mix.unMute()
        }
    }

    private var blackFilter: BaseFilterRender? = null

    override fun setTorch(enabled: Boolean) = camera.setTorch(enabled)

    override fun setBlackScreen(enabled: Boolean) {
        if (isReleased) return
        opsScope.launch {
            val gl = stream.getGlInterface()
            val filter = blackFilter ?: BlackFilterRender().also { blackFilter = it }
            if (enabled) gl.addFilter(filter) else gl.removeFilter(filter)
        }
        _state.update { it.copy(blackScreenOn = enabled) }
    }

    private var activeVideoEffectFilter: BaseFilterRender? = null

    override fun setVideoEffect(effect: VideoEffect) {
        if (isReleased) return
        opsScope.launch {
            val gl = stream.getGlInterface()
            activeVideoEffectFilter?.let { gl.removeFilter(it) }
            activeVideoEffectFilter = null
            val filter = when (effect) {
                VideoEffect.NONE -> null
                VideoEffect.GRAYSCALE -> GreyScaleFilterRender()
                VideoEffect.SEPIA -> SepiaFilterRender()
            }
            if (filter != null) {
                gl.addFilter(filter)
                activeVideoEffectFilter = filter
            }
            _state.update { it.copy(videoEffect = effect) }
        }
    }

    override fun setOverlayAudioInStream(
        overlayId: String,
        enabled: Boolean,
        donationPlayer: app.brix.streaming.overlay.DonationAudioPlayer?,
    ) = overlays.setAudioInStream(overlayId, enabled, donationPlayer)

    override fun showOverlay(
        overlayId: String,
        frames: List<OverlayFrame>,
        posX: Float,
        posY: Float,
        size: OverlaySize,
        durationMs: Long,
    ) = overlays.show(overlayId, frames, posX, posY, size, durationMs)

    override fun hideOverlay(overlayId: String) = overlays.hide(overlayId)

    override fun videoFrameSize(): android.graphics.Point? = overlays.videoFrameSize()

    override fun takeSnapshot(onResult: (android.graphics.Bitmap?) -> Unit) {
        if (isReleased) {
            onResult(null)
            return
        }
        runCatching { stream.getGlInterface().takePhoto { bitmap -> onResult(bitmap) } }
            .onFailure { onResult(null) }
    }

    override fun setOverlayConnectionState(overlayId: String, serviceId: String, connected: Boolean) =
        overlays.setConnectionState(overlayId, serviceId, connected)

    override fun forgetOverlayConnection(overlayId: String) = overlays.forgetConnection(overlayId)

    override fun attachLiveOverlay(overlayId: String, posX: Float, posY: Float, size: OverlaySize) =
        overlays.attachLive(overlayId, posX, posY, size)

    override fun updateLiveOverlayFrame(overlayId: String, bitmap: Bitmap) =
        overlays.updateLiveFrame(overlayId, bitmap)

    override fun detachLiveOverlay(overlayId: String) = overlays.detachLive(overlayId)

    override fun setLens(cameraId: String, zoom: Float): Boolean = camera.setLens(cameraId, zoom)

    override fun currentLensId(): String? = camera.currentLensId()

    override fun zoomByScale(scale: Float) = camera.zoomByScale(scale)

    override fun getZoomRange(): ClosedFloatingPointRange<Float>? = camera.getZoomRange()

    override fun tapFocus(view: View, x: Float, y: Float) = camera.tapFocus(view, x, y)

    override fun setVideoBitrate(bitrate: Int) {
        stream.setVideoBitrateOnFly(bitrate)
    }

    override var isReleased: Boolean = false
        private set

    @Synchronized
    override fun release() {
        if (isReleased) return
        isReleased = true
        prepared = false
        appliedCodec = null
        appliedKeyframeSec = null
        uptime.stop()
        recorder.stop()
        overlays.release()
        reconnector.cancel()
        opsScope.cancel()
        session.reset()
        activeGen = 0
        try {
            if (stream.isStreaming) stream.stopStream()
            stream.release()
        } catch (_: Exception) {
        }
    }

    override fun onConnectionStarted(url: String) {
        if (!session.matches(activeGen)) return
        updateSession(SessionPhase.Connecting)
    }

    override fun onConnectionSuccess() {
        if (!session.matches(activeGen)) return
        reconnector.reset()
        isReconnectAttempt = false
        _state.update { it.copy(reconnectAttempt = 0) }
        updateSession(SessionPhase.Live)
    }

    override fun onConnectionFailed(reason: String) {
        if (!session.matches(activeGen)) return
        // Another retry-ladder attempt failed — that is no reason to stop:
        // tunnels, elevators, and dead zones end, and the live stream continues.
        if (isReconnectAttempt) {
            if (updateSession(SessionPhase.Reconnecting)) reconnector.schedule()
            return
        }
        if (updateSession(SessionPhase.Failed)) {
            _state.update { it.copy(error = StreamError.unknown(reason, technical = reason)) }
        }
    }

    override fun onDisconnect() {
        if (!session.matches(activeGen)) return
        // Before 03.09, a disconnect immediately moved the session to Failed, ending
        // everything: there were no automatic attempts, and the manual button from
        // Failed did not work. Now it uses the same ladder as SRTLA.
        if (session.phase == SessionPhase.Live || session.phase == SessionPhase.Connecting) {
            if (updateSession(SessionPhase.Reconnecting)) {
                _state.update {
                    it.copy(error = StreamError(
                        code = StreamErrorCode.NETWORK_UNAVAILABLE,
                        message = "Connection lost",
                        retryable = true,
                    ))
                }
                reconnector.schedule()
            }
        }
    }

    override fun onAuthError() {
        if (!session.matches(activeGen)) return
        if (updateSession(SessionPhase.Failed)) {
            _state.update {
                it.copy(error = StreamError(
                    code = StreamErrorCode.AUTH_FAILED,
                    message = "Auth error",
                ))
            }
        }
    }

    override fun onAuthSuccess() = Unit

    override fun onNewBitrate(bitrate: Long) {
        // One channel row instead of an empty HUD block. WHIP has no bonding —
        // there is exactly one socket, so the share is always 100%; that is honest: the block
        // shows where the stream is going, not a comparison of paths. We accumulate bytes ourselves:
        // RootEncoder exposes no sent-byte counter, and onNewBitrate
        // arrives once per second and reports bits per second.
        sentBytes += bitrate / 8
        val link = ConnectionStat(
            type = "whip",
            score = 100,
            rtt = 0,
            enabled = true,
            bytesSent = sentBytes,
        )
        _state.update { it.copy(bitrateKbps = bitrate / 1000, connections = listOf(link)) }
    }

    private fun updateSession(to: SessionPhase, gen: Long = session.generation): Boolean {
        if (!session.transition(to, gen)) return false
        val status = to.toStatus()
        _state.update {
            val connectedAt = when {
                status == StreamStatus.Connected && it.connectedAtElapsedMs == null ->
                    SystemClock.elapsedRealtime()
                status == StreamStatus.Idle || status == StreamStatus.Failed -> null
                else -> it.connectedAtElapsedMs
            }
            it.copy(status = status, phase = session.phase, connectedAtElapsedMs = connectedAt)
        }
        // The same row as SRTLA: field logs and the bench (bench/) rely on it.
        // RTMP and WHIP had no transitions in the log at all.
        Log.i(tag, "updateSession $to (from gen=$gen)")
        return true
    }
}
