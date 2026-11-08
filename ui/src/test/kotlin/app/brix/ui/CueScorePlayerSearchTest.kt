package app.brix.ui

import app.brix.core.CueScorePlayer
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CueScorePlayerSearchTest {
    @Test
    fun `search parses participant images country and next page`() {
        val page = parseCueScorePlayerSearchResponse(
            """
            {
                "items": [{
                    "playerId": 12345,
                    "name": "Test Player",
                    "image": "https://img.cuescore.com/player.png",
                    "country": {"name": "Norway", "image": "https://cuescore.com/no.png"},
                    "firstname": "Test"
                }],
                "hasMore": true,
                "nextOffset": 25
            }
            """.trimIndent(),
            offset = 0,
        )

        assertEquals(
            listOf(CueScorePlayer("12345", "Test Player", "https://img.cuescore.com/player.png", "https://cuescore.com/no.png", "Norway")),
            page.players,
        )
        assertEquals(25, page.nextOffset)
    }

    @Test
    fun `search handles missing photo and nationality`() {
        val page = parseCueScorePlayerSearchResponse(
            """{"items":[{"playerId":12345,"name":"Test Player","image":null,"country":null}],"hasMore":false,"nextOffset":null}""",
            offset = 25,
        )

        assertEquals(listOf(CueScorePlayer("12345", "Test Player")), page.players)
        assertNull(page.nextOffset)
    }

    @Test
    fun `empty page may still have more results`() {
        val page = parseCueScorePlayerSearchResponse(
            """{"items":[],"hasMore":true,"nextOffset":50}""",
            offset = 25,
        )

        assertEquals(emptyList<CueScorePlayer>(), page.players)
        assertEquals(50, page.nextOffset)
    }

    @Test
    fun `no matches returns empty results`() {
        val page = parseCueScorePlayerSearchResponse(
            """{"items":[],"hasMore":false,"nextOffset":null}""",
            offset = 0,
        )

        assertEquals(emptyList<CueScorePlayer>(), page.players)
        assertNull(page.nextOffset)
    }

    @Test(expected = JSONException::class)
    fun `invalid pagination is rejected`() {
        parseCueScorePlayerSearchResponse("""{"items":[],"hasMore":true,"nextOffset":25}""", offset = 25)
    }

    @Test(expected = JSONException::class)
    fun `invalid player is rejected`() {
        parseCueScorePlayerSearchResponse(
            """{"items":[{"playerId":0,"name":"Invalid"}],"hasMore":false}""",
            offset = 0,
        )
    }

    @Test(expected = JSONException::class)
    fun `error object is not treated as no matches`() {
        parseCueScorePlayerSearchResponse("""{"error":"Unavailable"}""", offset = 0)
    }
}
