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
import com.pedro.library.rtmp.RtmpStream
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

class Streamer(context: Context) : ConnectChecker, LiveStreamer {

    private val appContext = context.applicationContext
    private val tag = "BrixStream"
    private val _state = MutableStateFlow(StreamState())
    override val state: StateFlow<StreamState> = _state.asStateFlow()

    // The live-stream clock tick is separate from state so the per-second time
    // update does not redraw the entire streamer screen. It is published from
    // the existing loop; no second timer is introduced.
    private val _uptimeTick = MutableStateFlow(0L)
    override val uptimeTick: StateFlow<Long> = _uptimeTick.asStateFlow()

    private val micMeter = MicLevelMeter()
    override val audioLevelLeft: StateFlow<Float> = micMeter.left
    override val audioLevelRight: StateFlow<Float> = micMeter.right
    override val audioClipping: StateFlow<Boolean> = micMeter.clipping

    private val stream = RtmpStream(appContext, this).apply {
        getGlInterface().autoHandleOrientation = true
    }

    private var profile: StreamProfile? = null
    private val session = StreamSession()
    private var currentUrl: String? = null
    private var activeGen = 0L

    /** The encoder has already been assembled by this prepare(). Without this
     *  flag, a repeated prepare() with a live preview would always fail — see [prepare]. */
    /** Accumulated bytes for the single channel row in the HUD. */
    @Volatile
    private var sentBytes = 0L

    @Volatile
    private var prepared = false

    /** Codec baked into the encoder by the last prepare(): bitrate can change
     *  live, but codec cannot; changing it requires rebuilding. */
    @Volatile
    private var appliedCodec: Codec? = null

    /** Keyframe interval baked in by the last prepare(). Like codec, it cannot
     *  change live, so edits require rebuilding; otherwise the setting silently
     *  remains unapplied until app restart. */
    @Volatile
    private var appliedKeyframeSec: Int? = null

    private val opsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // All three streamers use the same camera, but before 03.09 it had only been
    // fixed in SRTLA: here, focus taps could freeze, HAL calls ran on the caller's
    // thread, and camera defaults were completely ignored. The shared controller
    // fixes all of this at once and prevents the implementations from diverging again.
    private val camera = CameraController(
        appContext = appContext,
        stream = stream,
        scope = opsScope,
        profileFps = { profile?.video?.fps ?: 30 },
        isReleased = { isReleased },
        updateState = { transform -> _state.update(transform) },
    )

    // There is no other way to distinguish “could not connect on the first try”
    // from “disconnected mid-stream”: the transport calls onConnectionFailed for
    // both. The first is an error for the user; the second is a reason to continue the retry ladder.
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

    private val abr = QueueBitrateRegulator(
        targetBitrate = 6_000_000,
        minimumBitrate = 1_000_000,
    )

    private val uptime = UptimeTicker(
        scope = opsScope,
        publishTick = { tick -> _uptimeTick.value = tick },
        onTick = { stepAdaptiveBitrate() },
    )

    /**
     * Adaptive bitrate tick. RTMP's congestion signal is send-queue depth:
     * TCP does not lose packets; insufficient bandwidth grows the queue
     * and hence latency.
     */
    private fun stepAdaptiveBitrate() {
        if (!_state.value.adaptiveBitrateEnabled || !stream.isStreaming) return
        val client = runCatching { stream.getStreamClient() }.getOrNull() ?: return
        val items = runCatching { client.getItemsInCache() }.getOrNull() ?: return
        val cache = runCatching { client.getCacheSize() }.getOrNull() ?: return
        // One line per second with the ACTUAL queue fill: regulator thresholds
        // were derived from logic, not measured, and these figures are needed to
        // calibrate them. Separate tag, INFO level — readable without verbose,
        // which costs 13% of a core.
        Log.i(
            "BrixAbr",
            "fill=%.2f items=%d cache=%d kbps=%d".format(
                java.util.Locale.US,
                if (cache > 0) items.toDouble() / cache else 0.0,
                items,
                cache,
                abr.current() / 1000,
            ),
        )
        val newBitrate = runCatching { abr.update(items, cache) }.getOrNull() ?: return
        runCatching { stream.setVideoBitrateOnFly(newBitrate.toInt()) }
        _state.update { it.copy(adaptiveBitrateKbps = newBitrate / 1000) }
    }

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
     * One retry-ladder attempt. There is no transport reconnect as with SRT —
     * for RTMP this is a full stream restart.
     */
    @Synchronized
    private fun attemptReconnect() {
        val url = currentUrl ?: return
        if (!session.matches(activeGen)) return
        if (!updateSession(SessionPhase.Connecting)) return
        isReconnectAttempt = true
        runCatching { stream.stopStream() }
        stream.startStream(url)
        // stopStream() above stops camera capture; otherwise the live stream
        // would resume with a frozen image.
        camera.resumePreviewIfDetached()
    }

    override val isStreaming: Boolean get() = stream.isStreaming

    override fun configure(profile: StreamProfile, srtLatencyMs: Int) {
        this.profile = profile
        // Do NOT touch adaptiveBitrateEnabled here: it is a runtime toggle
        // owned by setAdaptiveBitrate(). Overwriting it from the saved profile
        // reset the manual choice on every press of “Start” — the toggle enabled
        // itself when streaming began. SrtlaStreamer has explicitly documented
        // this restriction from the start; it had not made its way here.
        abr.configure(
            target = profile.adaptiveBitrate.targetBitrateKbps * 1000L,
            min = profile.adaptiveBitrate.minimumBitrateKbps * 1000L,
        )
    }

    override fun prepare(): Boolean {
        // `StreamBase.prepareVideo` throws IllegalStateException while streaming,
        // recording, OR PREVIEWING. The UI calls prepare() twice: once when
        // connecting the preview (which is not live yet, so it succeeds) and
        // again on pressing “Start” — now with a live preview. The SRTLA path
        // prevents this with an early return on `prepared` and by stopping the
        // preview during reassembly; those fixes, like the others, had not made
        // it here, so RTMP/WHIP startup ALWAYS failed with “Failed to prepare stream.”
        // This is not new: the same failure was seen in the field on 31.08 when changing codecs.
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
                // The keyframeIntervalSec field has been in the model from the
                // beginning but was read NOWHERE: it was written to disk and did
                // nothing. The keyframe interval matters to viewers — it affects
                // how quickly video appears on connect and after a loss — so pass
                // it to the encoder instead of discarding it.
                iFrameInterval = p.keyframeIntervalSec,
                // Choose a profile based on hardware capabilities — see SrtlaStreamer.prepare().
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
                // The preview was stopped for reassembly — restore it, or the
                // screen will freeze on the last frame and the user may think the camera died.
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
            // The exception text used to go ONLY into StreamState.technical,
            // meaning nothing reached the log: the device showed “Failed to
            // prepare stream” and logcat was silent, leaving nothing to investigate.
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


    /** The microphone for the current source: since 17.09, the plain microphone
     *  is also wrapped in [OverlayAudioSource] — it calculates the level for the
     *  HUD indicator. All microphone settings are applied to the wrapped source. */
    private fun micSourceOrNull(): MicrophoneSource? = when (val source = stream.audioSource) {
        is MicrophoneSource -> source
        is app.brix.streaming.overlay.OverlayAudioSource -> source.micSourceForPreference()
        else -> null
    }

    /** Install our source if the library's bare microphone is active. */
    private fun ensureMeteredAudioSource() {
        // Only the library's bare microphone. Any other source was selected by
        // the scene (for example, internal audio during screen capture), and must
        // not be replaced with a microphone during stream reassembly.
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

    /** The preference resets along with the audio source, so this is called from
     *  configure() and when toggled with the button. */
    private fun applyMicSource() {
        val mic = micSourceOrNull() ?: return
        // HAL may reject the request, and that rejection must be visible;
        // otherwise the “at camera” choice silently remains the system choice.
        val applied = runCatching {
            mic.setPreferredDevice(MicDevices.deviceFor(appContext, preferredMic, preferredMicName))
        }.getOrDefault(false)
        if (!applied) {
            Log.w("BrixMic", "устройство не принято HAL: $preferredMic — звук с микрофона по выбору системы")
        }
    }

    // One monitor for the entire lifecycle — exactly what the comment on
    // release() had promised from the start, but the code lacked: the released
    // flag was checked without it, so ACTION_START from a notification could
    // enter start() concurrently with release() and start streaming on an already
    // released stream. SrtlaStreamer has had this protection since the audit (P0-2).
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
        // initialBitrateKbps exists precisely to start more cautiously than the
        // target and rise as the network proves itself.
        abr.reset((profile?.adaptiveBitrate?.initialBitrateKbps ?: 0) * 1000L)
        applyAbrBitrate()
        _state.update {
            it.copy(status = StreamStatus.Connecting, phase = session.phase, error = null, reconnectAttempt = 0)
        }
        stream.startStream(url)
        uptime.start()
        recorder.start()
        // RootEncoder also stops camera capture on stopStream(), so after
        // Stop->Start the preview remains on the last frame and looks like a dead
        // camera. Same call as in SrtlaStreamer.
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
        // The guard used to require the Live/Connecting phase, but this is called
        // from Failed — the notification's “Reconnect” button after a drop did
        // nothing, and recovery required a manual Stop→Start. The Failed→Reconnecting
        // transition is FORBIDDEN by the table (Failed only leads to Idle/Released),
        // so recover from failure via session.start(), as SrtlaStreamer does.
        val url = currentUrl ?: return
        // A manual press explicitly means “try now,” so restart the retry ladder
        // instead of continuing at thirty seconds.
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
     * Be careful with overlays: when donation audio is enabled, our [OverlayAudioSource]
     * mixer is the audio source, and replacing the source here removes it —
     * donations go silent during the live stream until the next switch. This is a
     * known limitation, not an accident: both mechanisms claim the same audio-source slot.
     */
    override fun setSceneAudio(
        mode: SceneAudio,
        projection: android.media.projection.MediaProjection?,
    ): Boolean {
        val source = when (mode) {
            // Use our own source even for a plain microphone: it also calculates
            // the level for the HUD indicator (MicLevelMeter), with mixing disabled.
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
            // Mute, gain, and device selection reset with the source — reapply
            // them, as elsewhere. Mute was not reapplied here before: changing
            // scenes unmuted the microphone.
            configureAudio(audioSettings)
            setMuted(_state.value.micMuted)
            true
        }.getOrElse {
            Log.e(tag, "звук сцены не переключён: ${it.message}")
            false
        }
    }


    override fun setAdaptiveBitrate(enabled: Boolean) {
        _state.update { it.copy(adaptiveBitrateEnabled = enabled) }
        // Enabled mid-stream — the encoder must get the regulator's current
        // decision immediately, not wait for the first change.
        if (enabled) applyAbrBitrate()
    }

    /**
     * Pass the regulator's decision to the encoder.
     *
     * This is a separate method because previously the bitrate was sent to the
     * encoder ONLY when it changed, not at startup. As a result, the regulator
     * used its initial value, the encoder kept the video-profile bitrate from
     * prepareVideo, and the live stream did not match what the HUD showed.
     */
    private fun applyAbrBitrate() {
        if (!_state.value.adaptiveBitrateEnabled) return
        val bitrate = abr.current()
        runCatching { stream.setVideoBitrateOnFly(bitrate.toInt()) }
        _state.update { it.copy(adaptiveBitrateKbps = bitrate / 1000) }
    }

    override fun updateAdaptiveBitrateLimits(targetKbps: Int, minKbps: Int, initialKbps: Int) {
        abr.configure(targetKbps * 1000L, minKbps * 1000L)
    }

    override fun setMuted(muted: Boolean) {
        _state.update { it.copy(micMuted = muted) }
        // Our source mutes itself after measuring the level (the indicator shows
        // the voice in gray while muted); the bare microphone uses library methods.
        when (val source = stream.audioSource) {
            is app.brix.streaming.overlay.OverlayAudioSource -> source.setMicMuted(muted)
            is MicrophoneSource -> if (muted) source.mute() else source.unMute()
        }
        // “Microphone + phone audio” scene: mute the microphone part while phone
        // audio continues. This source used to be missed, so the button showed
        // “off” while the voice still went to the live stream (23.09 audit).
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
        // Another retry-ladder attempt failed — no reason to stop: tunnels,
        // elevators, and dead zones end, while the live stream continues.
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
        // Before 03.09, a disconnect immediately moved the session to Failed and
        // that was the end: there were no automatic attempts, and the manual
        // button did not work from Failed. Now it uses the same retry ladder as SRTLA.
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
        // One channel line instead of an empty HUD block. RTMP has no bonding —
        // there is exactly one socket, so the share is always 100%, which is
        // accurate: the block shows where the stream is going, not a path
        // comparison. We accumulate bytes ourselves: RootEncoder exposes no sent
        // counter, and onNewBitrate arrives once a second with bits per second.
        sentBytes += bitrate / 8
        val link = ConnectionStat(
            type = "rtmp",
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
        // Same line as in SRTLA: used to analyze field logs and the test rig
        // (bench/). RTMP and WHIP transitions were not logged at all.
        Log.i(tag, "updateSession $to (from gen=$gen)")
        return true
    }
}
