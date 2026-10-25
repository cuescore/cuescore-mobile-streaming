package app.brix.streaming.overlay

import app.brix.streaming.MicLevelMeter
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Мьют как у IRL Pro (владелец, 26.10): индикатор продолжает показывать голос
 * серым, а в эфир уходит тишина. Раньше глушила библиотека внутри микрофона, и
 * индикатор видел ноль — мьют было не отличить от тишины.
 */
class OverlayAudioSourceMuteTest {

    private class Sink : GetMicrophoneData {
        val frames = mutableListOf<Frame>()
        override fun inputPCMData(frame: Frame) {
            frames += frame
        }
    }

    private fun loud(): Frame {
        val b = ByteArray(1024)
        for (i in b.indices step 2) {
            b[i] = 0x10
            b[i + 1] = 0x40 // 0x4010 = 16400
        }
        return Frame(b, 0, b.size, 123L)
    }

    private fun silent(f: Frame): Boolean {
        for (i in f.offset until f.offset + f.size) if (f.buffer[i].toInt() != 0) return false
        return true
    }

    @Test
    fun `muted mic still drives the meter but sends silence`() {
        val meter = MicLevelMeter(nowMs = { 0L }).apply { stereo = false }
        val sink = Sink()
        val source = OverlayAudioSource(meter = meter)
        source.attachSinkForTest(sink)
        source.setMicMuted(true)

        source.inputPCMData(loud())

        assertTrue("индикатор видит голос и при мьюте", meter.left.value > 0.5f)
        assertTrue("в эфир — тишина", silent(sink.frames.single()))
        assertTrue("метка времени кадра сохраняется", sink.frames.single().timeStamp == 123L)
    }

    @Test
    fun `unmuted mic passes the voice through`() {
        val sink = Sink()
        val source = OverlayAudioSource(meter = MicLevelMeter(nowMs = { 0L }))
        source.attachSinkForTest(sink)
        source.setMicMuted(false)

        source.inputPCMData(loud())

        assertTrue(!silent(sink.frames.single()))
    }
}
