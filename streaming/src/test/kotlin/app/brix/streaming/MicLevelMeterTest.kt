package app.brix.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Level meter: clipping must be visible, with at most 10 screen updates per second. */
class MicLevelMeterTest {

    private var now = 0L
    private fun meter() = MicLevelMeter(nowMs = { now }).apply { stereo = false }

    /** Mono PCM16 frame containing one repeated sample value. */
    private fun frame(sample: Int, samples: Int = 512): ByteArray {
        val b = ByteArray(samples * 2)
        for (i in 0 until samples) {
            b[i * 2] = (sample and 0xFF).toByte()
            b[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return b
    }

    @Test
    fun `clipping stays visible for a second and a half after the peak`() {
        val m = meter()
        m.submit(frame(32_700))
        assertTrue(m.clipping.value)

        // Then quiet frames every 20 ms, as from a microphone.
        repeat(70) {
            now += 20
            m.submit(frame(1_000))
        }
        assertTrue("раньше гасло на следующем же кадре, через 20 мс", m.clipping.value)

        now = 1_600
        m.submit(frame(1_000))
        assertFalse(m.clipping.value)
    }

    @Test
    fun `level is published at most ten times a second`() {
        val m = meter()
        m.submit(frame(8_000))
        val first = m.left.value
        assertTrue(first > 0f)

        // Louder, but after 50 ms it is too early for a screen update.
        now = 50
        m.submit(frame(30_000))
        assertEquals("раньше значение менялось на каждом кадре", first, m.left.value, 0f)

        now = 100
        m.submit(frame(30_000))
        assertTrue(m.left.value > first)
    }

    @Test
    fun `a short peak between publishes is not lost`() {
        val m = meter()
        m.submit(frame(1_000))
        now = 40
        m.submit(frame(30_000)) // Peak in the middle of the window
        repeat(4) {
            now += 15
            m.submit(frame(1_000))
        }
        now = 100
        m.submit(frame(1_000))
        val peak = MicLevelMeter.scale(30_000)
        assertEquals("на экран попадает максимум окна, а не последний кадр", peak, m.left.value, 0.01f)
    }

    @Test
    fun `silence publishes nothing new`() {
        val m = meter()
        m.submit(frame(0))
        now = 500
        m.submit(frame(0))
        assertEquals(0f, m.left.value, 0f)
        assertFalse(m.clipping.value)
    }
}
