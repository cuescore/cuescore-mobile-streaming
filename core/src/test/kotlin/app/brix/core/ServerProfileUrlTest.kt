package app.brix.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerProfileUrlTest {

    private fun rtmp(baseUrl: String, key: String = "") =
        ServerProfile(id = "s", name = "n", type = ServerType.RTMP, baseUrl = baseUrl, streamId = key)

    @Test
    fun `ключ приклеивается к адресу RTMP`() {
        assertEquals(
            "rtmp://eun10.contribute.live-video.net/app/live_123",
            rtmp("rtmp://eun10.contribute.live-video.net/app", "live_123").connectUrl(),
        )
    }

    @Test
    fun `лишние слэши не задваиваются`() {
        // The address is often copied from the platform with a trailing slash, and the key
        // with a leading one. Joining them must not produce "app//live_123".
        assertEquals(
            "rtmp://host/app/live_123",
            rtmp("rtmp://host/app/", "/live_123").connectUrl(),
        )
    }

    @Test
    fun `пробелы вокруг ключа срезаются`() {
        // The key is almost always pasted into the field from the clipboard, along with a
        // line break or whitespace.
        assertEquals("rtmp://host/app/live_123", rtmp("rtmp://host/app", "  live_123\n").connectUrl())
    }

    @Test
    fun `без ключа адрес не меняется`() {
        // Profiles created before the field was added keep the key directly in the address.
        val old = rtmp("rtmp://host/app/live_OLDKEY")
        assertEquals("rtmp://host/app/live_OLDKEY", old.connectUrl())
    }

    private fun srtla(baseUrl: String, id: String = "") =
        ServerProfile(id = "s", name = "n", type = ServerType.SRTLA, baseUrl = baseUrl, streamId = id)

    @Test
    fun `у SRTLA идентификатор уходит в параметр streamid`() {
        assertEquals(
            "srtla://host:5000?streamid=live/stream/brix?srtauth=KEY",
            srtla("srtla://host:5000", "live/stream/brix?srtauth=KEY").connectUrl(),
        )
    }

    @Test
    fun `прежний streamid в адресе отбрасывается, а не задваивается`() {
        // Otherwise, filling in the field would result in two streamid values in a row, and
        // the receiver would reject the connection.
        assertEquals(
            "srtla://host:5000?streamid=новый",
            srtla("srtla://host:5000?streamid=старый", "новый").connectUrl(),
        )
    }

    @Test
    fun `у SRTLA без заполненного поля адрес не меняется`() {
        // Profiles created before the field was added keep streamid directly in the address.
        val old = srtla("srtla://host:5000?streamid=live/stream/x?srtauth=KEY")
        assertEquals("srtla://host:5000?streamid=live/stream/x?srtauth=KEY", old.connectUrl())
    }

    @Test
    fun `у WHIP ключ тоже не приклеивается`() {
        val whip = ServerProfile(
            id = "s", name = "n", type = ServerType.WHIP,
            baseUrl = "https://host/whip", streamId = "k",
        )
        assertEquals("https://host/whip", whip.connectUrl())
    }
}
