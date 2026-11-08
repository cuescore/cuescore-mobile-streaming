package app.brix.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Import servers from a link: exactly one remains active afterward. */
class ImportServersTest {

    private fun server(id: String, enabled: Boolean) =
        ServerProfile(id = id, name = id, type = ServerType.SRTLA, baseUrl = "srtla://h:5000", enabled = enabled)

    private fun List<ServerProfile>.active() = filter { it.enabled }.map { it.id }

    @Test
    fun `own active server stays active, imported ones arrive disabled`() {
        val merged = mergeImportedServers(
            listOf(server("own", true)),
            listOf(server("a", true), server("b", true)),
        )
        assertEquals("раньше горели все три", listOf("own"), merged.active())
        assertEquals(listOf("own", "a", "b"), merged.map { it.id })
    }

    @Test
    fun `without an active server the first enabled import becomes active`() {
        val merged = mergeImportedServers(
            listOf(server("own", false)),
            listOf(server("a", false), server("b", true), server("c", true)),
        )
        assertEquals(listOf("b"), merged.active())
    }

    @Test
    fun `without any enabled server the first import becomes active`() {
        val merged = mergeImportedServers(emptyList(), listOf(server("a", false), server("b", false)))
        assertEquals(listOf("a"), merged.active())
    }
}
