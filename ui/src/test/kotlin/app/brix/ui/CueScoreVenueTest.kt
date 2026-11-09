package app.brix.ui

import app.brix.core.CueScoreTable
import app.brix.core.ButtonAction
import app.brix.core.Scene
import app.brix.core.defaultAppSettings
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CueScoreVenueTest {
    @Test
    fun `venue search parses results and pagination`() {
        val page = parseCueScoreVenueSearchResponse(
            """{"items":[{"venueId":123,"name":"Test venue","address":"Test address"}],"hasMore":true,"nextOffset":25}""",
            offset = 0,
        )
        assertEquals(listOf(CueScoreVenue("123", "Test venue", "Test address")), page.venues)
        assertEquals(25, page.nextOffset)
    }

    @Test
    fun `empty venue search has no next page`() {
        val page = parseCueScoreVenueSearchResponse(
            """{"items":[],"hasMore":false,"nextOffset":null}""",
            offset = 0,
        )
        assertTrue(page.venues.isEmpty())
        assertNull(page.nextOffset)
    }

    @Test
    fun `venue lookup returns named tables with venue identity`() {
        val tables = parseCueScoreTablesResponse(
            """{"venueId":123,"name":"Test venue","tables":[{"tableId":456,"name":"1"},{"tableId":789,"name":"Match table"}]}""",
            "123",
        )
        assertEquals(
            listOf(CueScoreTable("456", "1", "123", "Test venue"), CueScoreTable("789", "Match table", "123", "Test venue")),
            tables,
        )
    }

    @Test
    fun `venue without tables returns empty list`() {
        assertTrue(parseCueScoreTablesResponse("""{"venueId":123,"name":"Test venue","tables":[]}""", "123").isEmpty())
    }

    @Test(expected = JSONException::class)
    fun `wrong venue response is rejected`() {
        parseCueScoreTablesResponse("""{"venueId":321,"name":"Test venue","tables":[]}""", "123")
    }

    @Test(expected = JSONException::class)
    fun `venue errors are not treated as empty table lists`() {
        parseCueScoreTablesResponse("""{"error":"Not found"}""", "123")
    }

    @Test(expected = JSONException::class)
    fun `invalid pagination is rejected`() {
        parseCueScoreVenueSearchResponse("""{"items":[],"hasMore":true,"nextOffset":0}""", 0)
    }

    @Test
    fun `player and table scoreboard URLs are classified independently`() {
        val player = "${CUESCORE_SCOREBOARD_URL_PREFIX}456"
        val table = "${CUESCORE_TABLE_SCOREBOARD_URL_PREFIX}456"
        assertTrue(isCueScoreScoreboardUrl(player))
        assertFalse(isCueScoreScoreboardUrl(player, table = true))
        assertTrue(isCueScoreScoreboardUrl(table, table = true))
        assertFalse(isCueScoreScoreboardUrl(table))
    }

    @Test
    fun `skipping both overlay steps adds nothing`() {
        val original = defaultAppSettings()
        assertEquals(original, original.withCueScoreOverlays(null, null))
    }

    @Test
    fun `player and table overlays can be added independently or together`() {
        for ((player, table) in listOf("123" to null, null to "456", "123" to "456")) {
            val settings = defaultAppSettings().copy(
                scenes = listOf(Scene("scene", "Camera")),
                selectedSceneId = "scene",
            ).withCueScoreOverlays(player, table)
            assertEquals(listOfNotNull(player, table).size, settings.widgets.size)
            assertEquals(player != null, settings.widgets.any { isCueScoreScoreboardUrl(it.url) })
            assertEquals(table != null, settings.widgets.any { isCueScoreScoreboardUrl(it.url, true) })
            assertEquals(player != null, settings.quickButtons.slots.any { it.action == ButtonAction.CUESCORE_SCOREBOARD })
            assertEquals(table != null, settings.quickButtons.slots.any { it.action == ButtonAction.CUESCORE_TABLE_SCOREBOARD })
            settings.widgets.forEach {
                assertTrue(it.enabled)
                assertEquals(1f, it.width)
                assertEquals(1f, it.height)
                assertTrue(it.id in settings.selectedScene()!!.widgetIds)
            }
            val cells = settings.quickButtons.slots.map { it.rowIndex to it.columnPosition }
            assertEquals(cells.size, cells.distinct().size)
            assertEquals(settings, settings.withCueScoreOverlays(player, table))
        }
    }

    @Test
    fun `changing a table leaves player overlay untouched`() {
        val original = defaultAppSettings().withCueScoreOverlays("123", "456")
        val player = original.widgets.single { isCueScoreScoreboardUrl(it.url) }
        val updated = original.withCueScoreOverlays(null, "789")
        assertEquals(player, updated.widgets.single { isCueScoreScoreboardUrl(it.url) })
        assertEquals("${CUESCORE_TABLE_SCOREBOARD_URL_PREFIX}789", updated.widgets.single { isCueScoreScoreboardUrl(it.url, true) }.url)
        assertEquals(original.widgets.map { it.id }, updated.widgets.map { it.id })
    }
}
