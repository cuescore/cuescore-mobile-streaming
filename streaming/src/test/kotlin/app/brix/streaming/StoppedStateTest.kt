package app.brix.streaming

import app.brix.core.ConnectionStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * After stopping a stream, state retains no stale HUD metrics.
 *
 * 14.09: three reset implementations diverged. SRTLA did not clear links,
 * leaving frozen Wi-Fi and cellular bitrates in the HUD as if still streaming.
 * Nobody reset `adaptiveBitrateKbps`, although the HUD uses it when ABR is
 * enabled. This test enforces a shared reset to prevent future divergence.
 */
class StoppedStateTest {

    private fun live() = StreamState(
        status = StreamStatus.Connected,
        message = "идёт",
        bitrateKbps = 2500,
        adaptiveBitrateKbps = 2300,
        connections = listOf(ConnectionStat(type = "WIFI", score = 10, rtt = 40, enabled = true)),
        connectedAtElapsedMs = 1000,
    )

    @Test
    fun `цифры, которые показывает HUD, обнуляются`() {
        val s = live().stopped()
        assertEquals(0L, s.bitrateKbps)
        // HUD shows adaptiveBitrateKbps with ABR enabled, so verify that value.
        assertEquals(0L, s.adaptiveBitrateKbps)
        assertTrue("список каналов пуст", s.connections.isEmpty())
        assertNull(s.connectedAtElapsedMs)
        assertEquals(StreamStatus.Idle, s.status)
    }

    @Test
    fun `настройки эфира сбросом не трогаются`() {
        // Reset readings, not user choices: microphone mute and torch
        // must remain unchanged after Stop.
        val s = live().copy(micMuted = true, torchOn = true).stopped()
        assertTrue(s.micMuted)
        assertTrue(s.torchOn)
    }
}
