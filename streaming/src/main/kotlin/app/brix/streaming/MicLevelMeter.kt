package app.brix.streaming

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * MICROPHONE level per channel—for the indicator on the streamer's screen.
 *
 * Calculated from a microphone PCM frame with gain and mute already applied, but BEFORE
 * mixing in donations (the owner, 17.09: the indicator should show the voice, not the sum
 * of everything sent to the live stream—otherwise an alert looks like shouting into the mic).
 *
 * **Why peak, not average.** The streamer needs two answers: “is there any audio at all”
 * and “is the input overloaded?” Both are visible in the peak; the RMS value
 * is smoother, but clipping is not visible in it.
 *
 * Value is a fraction from 0..1 on a decibel scale (-60 dB and below is zero), because
 * for voice, a linear scale stays in the bottom tenth almost all the time.
 * Decay is added artificially: without it, the bar flashes at every pause between
 * words and looks like a malfunction.
 */
class MicLevelMeter(
    private val decayPerFrame: Float = 0.25f,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    private val _left = MutableStateFlow(0f)

    /** Left channel (the only channel in mono). */
    val left: StateFlow<Float> = _left

    private val _right = MutableStateFlow(0f)
    val right: StateFlow<Float> = _right

    private val _clipping = MutableStateFlow(false)
    val clipping: StateFlow<Boolean> = _clipping

    @Volatile
    var stereo: Boolean = true

    fun submit(pcm16: ByteArray, offset: Int = 0, size: Int = pcm16.size - offset) {
        var peakL = 0
        var peakR = 0
        // Downsampling: a frame contains thousands of samples, and the peak barely changes,
        // while this is calculated in the audio recording stream, where extra cycles are undesirable.
        // The step is a multiple of a stereo frame (4 bytes), otherwise the channels will be swapped.
        val step = if (stereo) 16 else 8
        val end = offset + size
        var i = offset
        while (i + 1 < end) {
            val l = sampleAt(pcm16, i)
            peakL = max(peakL, abs(l))
            if (stereo && i + 3 < end) {
                peakR = max(peakR, abs(sampleAt(pcm16, i + 2)))
            }
            i += step
        }
        // Internally, every frame (decay is calculated per frame); externally, no more often than
        // PUBLISH_MS. Previously every frame value went into StateFlow and the HUD
        // re-rendered ~13 times/s while there was sound in the room (04.10 test setup).
        // The maximum over the window is published, not the last frame: otherwise, a word's peak
        // occurring between publications would never reach the screen.
        levelL = max(scale(peakL), levelL - decayPerFrame)
        levelR = if (stereo) max(scale(peakR), levelR - decayPerFrame) else levelL
        windowL = max(windowL, levelL)
        windowR = max(windowR, levelR)

        val now = nowMs()
        // Time-based overload latch: previously, the flag stayed on for exactly one frame
        // (~20 ms) and almost always slipped between screen frames.
        val clipNow = max(peakL, peakR) >= CLIP_SAMPLE
        if (clipNow) clipUntil = now + CLIP_HOLD_MS
        val clipped = now < clipUntil

        // The start of an overload is published immediately, without waiting for the window.
        if (now - lastPublish >= PUBLISH_MS || (clipped && !_clipping.value)) {
            _left.value = windowL
            _right.value = windowR
            _clipping.value = clipped
            lastPublish = now
            windowL = levelL
            windowR = levelR
        }
    }

    private var levelL = 0f
    private var levelR = 0f
    private var windowL = 0f
    private var windowR = 0f
    private var clipUntil = Long.MIN_VALUE
    private var lastPublish = Long.MIN_VALUE / 2

    private fun sampleAt(pcm16: ByteArray, index: Int): Int =
        (((pcm16[index].toInt() and 0xFF) or (pcm16[index + 1].toInt() shl 8)).toShort()).toInt()

    fun reset() {
        levelL = 0f
        levelR = 0f
        windowL = 0f
        windowR = 0f
        clipUntil = Long.MIN_VALUE
        _left.value = 0f
        _right.value = 0f
        _clipping.value = false
    }

    companion object {
        private const val PUBLISH_MS = 100L
        private const val CLIP_HOLD_MS = 1_500L
        private const val CLIP_SAMPLE = 32_000

        /** Decibels, not a linear scale: on a linear scale, voice stays
         *  in the bottom tenth of the bar almost all the time. −60 dB and below is zero. */
        internal fun scale(peak: Int): Float {
            val normalized = peak / 32768f
            val db = if (normalized <= 0.0001f) -60f else 20f * log10(normalized)
            return ((db + 60f) / 60f).coerceIn(0f, 1f)
        }
    }
}
