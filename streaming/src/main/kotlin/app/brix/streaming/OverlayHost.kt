package app.brix.streaming

import android.graphics.Bitmap
import android.graphics.Point
import android.os.SystemClock
import android.util.Log
import app.brix.core.DonationService
import app.brix.streaming.overlay.DonationAudioPlayer
import app.brix.streaming.overlay.NativeOverlayRenderer
import app.brix.streaming.overlay.OverlayAudioSource
import app.brix.streaming.overlay.OverlayFrame
import app.brix.streaming.overlay.OverlaySize
import com.pedro.library.base.StreamBase
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything concerning overlays in the frame: donation alerts, live overlays
 * (browser widget snapshots), donation audio mixing into the stream and
 * widget connectivity for the HUD.
 *
 * Extracted from [SrtlaStreamer], which held six unrelated responsibilities
 * and grew to fifteen hundred lines. Nothing changed externally:
 * [LiveStreamer] remains the same interface; the streamer delegates here.
 *
 * Dependencies are narrow and injected: media pipeline [stream] (GL filter
 * chain and audio-source replacement), shared coroutine [scope], [updateState]
 * for HUD-visible StreamState fields and [isReleased], since the owner may
 * already be destroyed when a delayed call arrives.
 */
internal class OverlayHost(
    // StreamBase, not a specific transport: RtmpStream, WhipStream and
    // SrtlaStream all inherit it, and it declares the GL chain and audio-source
    // replacement needed by overlays. Before 03.09 the host knew only SRTLA,
    // leaving eight empty donation-overlay stubs in RTMP and WHIP.
    private val stream: StreamBase,
    /** Latched microphone mute: reapply after every audio-source change,
     *  otherwise enabling the overlay mixer unmutes the microphone. */
    private val micMuted: () -> Boolean,
    /** Profile microphone gain: reapply after audio-source changes, like mute,
     *  otherwise enabling donation audio silently resets the level to one. */
    private val micGain: () -> Float,
    /** Shared level meter: the donation mixer must feed the same HUD
     *  indicator as the normal microphone. */
    private val micMeter: MicLevelMeter,
    private val scope: CoroutineScope,
    private val isReleased: () -> Boolean,
    private val updateState: ((StreamState) -> StreamState) -> Unit,
) {
    private val tag = "BrixStream"

    /** Reapply BOTH mute and gain to the current audio source. */
    private fun applyMicMute() {
        when (val source = stream.audioSource) {
            is OverlayAudioSource -> {
                source.setMicMuted(micMuted())
                source.setMicGain(micGain())
            }
            is MicrophoneSource -> {
                if (micMuted()) source.mute() else source.unMute()
                source.microphoneVolume = micGain()
            }
            is com.pedro.encoder.input.sources.audio.MixAudioSource -> {
                if (micMuted()) source.mute() else source.unMute()
                source.microphoneVolume = micGain()
            }
            else -> Unit
        }
    }

    // Each overlay ID has its own renderer and animation job so one alert
    // never cancels or overwrites another. RootEncoder's GlInterface is a
    // genuine filter chain (addFilter/removeFilter), allowing multiple
    // ImageFilterRender instances simultaneously.
    private val renderers = ConcurrentHashMap<String, NativeOverlayRenderer>()
    private val jobs = ConcurrentHashMap<String, Job>()

    private var mixer: OverlayAudioSource? = null

    // Overlays whose donation audio is currently mixed into the stream, by ID.
    // Several can play at once (OverlayAudioSource sums them), so use a map,
    // not a single replaceable player.
    private val mixPlayers = ConcurrentHashMap<String, DonationAudioPlayer>()

    // Connectivity per overlay (donation widgets only), reduced to
    // StreamState.donationWidgetConnected using "at least one".
    private val connectionStates = ConcurrentHashMap<String, Boolean>()

    /** overlayId -> service (see [app.brix.core.DonationService]): lets the HUD
     *  show each service's marker separately instead of one shared dot. */
    private val overlayServices = ConcurrentHashMap<String, String>()

    fun setAudioInStream(overlayId: String, enabled: Boolean, donationPlayer: DonationAudioPlayer?) {
        if (isReleased()) return
        scope.launch {
            try {
                if (enabled && donationPlayer != null) {
                    mixPlayers[overlayId] = donationPlayer
                } else {
                    mixPlayers.remove(overlayId)
                }
                if (mixPlayers.isNotEmpty() && mixer == null) {
                    val newMixer = OverlayAudioSource(meter = micMeter)
                    newMixer.startMixing()
                    stream.changeAudioSource(newMixer)
                    mixer = newMixer
                    // New sources start unmuted: reapply latched mute,
                    // otherwise enabling donation audio silently opens
                    // a microphone the streamer believes is disabled.
                    applyMicMute()
                } else if (mixPlayers.isEmpty() && mixer != null) {
                    mixer = null
                    stream.changeAudioSource(OverlayAudioSource(meter = micMeter))
                    applyMicMute()
                }
                mixer?.setDonationPlayers(mixPlayers.values.toSet())
            } catch (e: Exception) {
                Log.e(tag, "setOverlayAudioInStream failed", e)
            }
        }
    }

    fun show(
        overlayId: String,
        frames: List<OverlayFrame>,
        posX: Float,
        posY: Float,
        size: OverlaySize,
        durationMs: Long,
    ) {
        if (isReleased() || frames.isEmpty()) return
        val gl = stream.getGlInterface()
        val renderer = renderers.getOrPut(overlayId) { NativeOverlayRenderer() }
        renderer.attach(gl)
        renderer.setTransform(posX, posY, size)
        jobs[overlayId]?.cancel()
        jobs[overlayId] = scope.launch {
            try {
                val start = SystemClock.elapsedRealtime()
                var idx = 0
                // Brief fade-in, frames for the requested duration, then fade-out:
                // donation alerts should not appear and disappear abruptly.
                renderer.setAlpha(0f)
                while (SystemClock.elapsedRealtime() - start < durationMs) {
                    val frame = frames[idx % frames.size]
                    renderer.setFrame(frame.bitmap)
                    val elapsed = (SystemClock.elapsedRealtime() - start).toFloat()
                    renderer.setAlpha(alphaFor(elapsed, durationMs))
                    delay(frame.durationMs.coerceAtLeast(16L))
                    idx++
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Expected: hide() cancels this job when display time expires
                // or another alert replaces it. Not a failure; structured
                // concurrency requires rethrowing the exception, not
                // swallowing it as an error.
                throw e
            } catch (e: Exception) {
                Log.e(tag, "showOverlay animation failed (overlayId=$overlayId)", e)
            } finally {
                renderer.setAlpha(0f)
                jobs.remove(overlayId)
                updateState { it.copy(overlayVisible = jobs.isNotEmpty()) }
            }
        }
        updateState { it.copy(overlayVisible = true) }
    }

    fun hide(overlayId: String) {
        if (isReleased()) return
        jobs.remove(overlayId)?.cancel()
        val renderer = renderers[overlayId]
        scope.launch {
            // Remove the filter only if this overlay has not started showing
            // again meanwhile. show() reuses the renderer; attach() is a no-op
            // while the filter remains attached, so a delayed detach silently
            // hid the newly displayed alert (audit 23.09).
            if (!jobs.containsKey(overlayId)) renderer?.detach(stream.getGlInterface())
        }
        updateState { it.copy(overlayVisible = jobs.isNotEmpty()) }
    }

    fun videoFrameSize(): Point? =
        if (isReleased()) null else runCatching { stream.getGlInterface().encoderSize }.getOrNull()

    fun setConnectionState(overlayId: String, serviceId: String, connected: Boolean) {
        if (isReleased()) return
        connectionStates[overlayId] = connected
        overlayServices[overlayId] = serviceId
        publishConnections()
    }

    /** Overlay removed from the screen: forget it completely. */
    fun forgetConnection(overlayId: String) {
        if (isReleased()) return
        connectionStates.remove(overlayId)
        overlayServices.remove(overlayId)
        publishConnections()
    }

    private fun publishConnections() {
        val byService = connectionStates.entries
            .groupBy({ overlayServices[it.key] ?: DonationService.UNKNOWN.id }, { it.value })
            .mapValues { (_, states) -> states.any { it } }
        updateState {
            it.copy(
                donationWidgetConnected = connectionStates.values.any { v -> v }.takeIf { connectionStates.isNotEmpty() },
                donationServices = byService,
            )
        }
    }

    // Live overlays (e.g. browser widget snapshots) share the renderer map with
    // donation alerts; distinct IDs prevent conflicts. They bypass animation
    // jobs entirely: attach once at full opacity and receive new frames
    // until detached.
    fun attachLive(overlayId: String, posX: Float, posY: Float, size: OverlaySize) {
        if (isReleased()) return
        val renderer = renderers.getOrPut(overlayId) { NativeOverlayRenderer() }
        renderer.attach(stream.getGlInterface())
        renderer.setTransform(posX, posY, size)
        renderer.setAlpha(1f)
    }

    fun updateLiveFrame(overlayId: String, bitmap: Bitmap) {
        if (isReleased()) return
        renderers[overlayId]?.setFrame(bitmap)
    }

    fun detachLive(overlayId: String) {
        if (isReleased()) return
        val renderer = renderers.remove(overlayId) ?: return
        scope.launch {
            renderer.detach(stream.getGlInterface())
        }
    }

    /** Remove everything: animations, renderers from the filter chain, connectivity.
     *  Called by [SrtlaStreamer.release] before releasing the stream itself. */
    fun release() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        renderers.values.forEach { it.detach(stream.getGlInterface()) }
        renderers.clear()
        connectionStates.clear()
        overlayServices.clear()
    }

    private fun alphaFor(elapsedMs: Float, totalMs: Long): Float {
        val fade = 250f
        return when {
            elapsedMs < fade -> elapsedMs / fade
            elapsedMs >= totalMs - fade -> ((totalMs - elapsedMs) / fade).coerceAtLeast(0f)
            else -> 1f
        }.coerceIn(0f, 1f)
    }
}
