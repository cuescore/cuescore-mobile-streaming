package app.brix.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Convert link weights from 0..100 to ranks 1..10 (14.09).
 *
 * Modifies existing users' saved settings, so test rather than guess:
 * a bug silently changes traffic distribution or disables a link entirely.
 */
class ChannelWeightMigrationTest {

    private fun withWeights(vararg weights: Int): AppSettings {
        val priorities = weights.mapIndexed { i, w ->
            ConnectionPriority(name = "LINK$i", enabled = w > 0, weight = w)
        }
        return AppSettings(
            streamProfiles = listOf(
                StreamProfile(id = "p", name = "p", srtConnectionPriorities = priorities),
            ),
        )
    }

    private fun weightsOf(s: AppSettings) =
        s.streamProfiles.first().srtConnectionPriorities.map { it.weight }

    @Test
    fun `старые значения делятся на десять`() {
        val out = weightsOf(withWeights(80, 20, 100).migrate())
        assertEquals(listOf(8, 2, 10), out)
    }

    @Test
    fun `мелкие старые значения не схлопываются в ноль`() {
        // Integer division of 1..9 would give 0, silently disabling the link.
        val out = weightsOf(withWeights(11, 15).migrate())
        assertEquals(listOf(2, 2), out)
    }

    @Test
    fun `ноль остаётся нулём — это выключенный канал`() {
        assertEquals(listOf(0), weightsOf(withWeights(0).migrate()))
    }

    @Test
    fun `значения в новом диапазоне не трогаются`() {
        assertEquals(listOf(1, 5, 10), weightsOf(withWeights(1, 5, 10).migrate()))
    }

    @Test
    fun `миграция идемпотентна`() {
        val once = withWeights(80, 20).migrate()
        assertEquals(weightsOf(once), weightsOf(once.migrate()))
    }
}
