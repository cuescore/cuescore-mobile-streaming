package app.brix.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {

    @Test
    fun `рабочие адреса принимаются`() {
        assertTrue(looksLikeServerUrl("srtla://example.com:5000"))
        assertTrue(looksLikeServerUrl("rtmp://live.twitch.tv/app"))
        assertTrue(looksLikeServerUrl("https://whip.example.com/whip/endpoint?token=abc"))
        // java.net.URI returns host = null for names containing underscores
        assertTrue(looksLikeServerUrl("srtla://belabox_home.local:5000"))
        // and throws for | and spaces in streamid
        assertTrue(looksLikeServerUrl("srt://1.2.3.4:8890?streamid=publish:live|key one"))
        assertTrue(looksLikeServerUrl("srt://[2001:db8::1]:8890"))
        assertTrue(looksLikeServerUrl("rtmp://user:pass@host/app"))
    }

    @Test
    fun `пустое и без схемы отвергаются`() {
        assertFalse(looksLikeServerUrl(""))
        assertFalse(looksLikeServerUrl("   "))
        assertFalse(looksLikeServerUrl("myserver.ru:5000"))
        assertFalse(looksLikeServerUrl("srtla://"))
        assertFalse(looksLikeServerUrl("srtla://:5000"))
        assertFalse(looksLikeServerUrl("://host"))
    }
}
