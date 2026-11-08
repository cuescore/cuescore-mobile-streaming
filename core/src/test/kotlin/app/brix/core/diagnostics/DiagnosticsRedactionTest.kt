package app.brix.core.diagnostics

import app.brix.core.AppSettings
import app.brix.core.AudioSettings
import app.brix.core.StreamProfile
import app.brix.core.ChatSettings
import app.brix.core.KickIntegration
import app.brix.core.MoblinkSettings
import app.brix.core.OverlayConfig
import app.brix.core.Scene
import app.brix.core.ServerProfile
import app.brix.core.ServerType
import app.brix.core.TwitchIntegration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diagnostics export must not identify the owner.
 *
 * This isn't hypothetical caution: until Sep 14, the only field hidden here was
 * `passphrase`, which was known to always be empty (the app provided no way to set
 * it). Everything else went out in the clear—the stream key, personal server
 * address, channel names, and DonationAlerts widget token. The "Share diagnostics"
 * button was sharing exactly what it must not.
 */
class DiagnosticsRedactionTest {

    private fun settings() = AppSettings(
        serverProfiles = listOf(
            ServerProfile(
                id = "rtmp",
                name = "twitch",
                type = ServerType.RTMP,
                baseUrl = "rtmp://ingest.example.com/app/live_111_SECRETKEY",
            ),
            ServerProfile(
                id = "srtla",
                name = "bbox",
                type = ServerType.SRTLA,
                baseUrl = "srtla://example.com:5000",
                streamId = "publish/live?srtauth=SECRETAUTH",
                latencyMs = 4321,
                preferIpv4 = true,
            ),
        ),
        moblink = MoblinkSettings(password = "SECRETRELAY"),
        chat = ChatSettings(
            twitchChannel = "SECRETCHANNEL",
            vkClientSecret = "SECRETVK",
        ),
        overlays = listOf(OverlayConfig(id = "o", url = "https://donationalerts.com/widget/SECRETTOKEN")),
        scenes = listOf(Scene(id = "s", name = "SECRETSCENE", imageUri = "/storage/emulated/0/SECRETPATH.png")),
        // Legacy migration field. This is what the first redaction missed: the
        // `overlays` list was scrubbed, but this duplicate was not.
        overlayUrl = "https://www.donationalerts.com/widget/alerts?token=SECRETWIDGET",
        kick = KickIntegration(clientId = "SECRETKICKID", clientSecret = "SECRETKICKSECRET", refreshToken = "SECRETKICKREFRESH"),
        twitch = TwitchIntegration(accessToken = "SECRETACCESS", refreshToken = "SECRETREFRESH", userId = "1", login = "SECRETLOGIN"),
        // A headset name can be the owner’s name (“Dima’s Buds”), both in
        // the general audio settings and in the legacy profile field (audit 23.09).
        audio = AudioSettings(micGain = 1.7f, micDeviceName = "SECRETHEADSET"),
        streamProfiles = listOf(
            @Suppress("DEPRECATION")
            StreamProfile(id = "p", name = "p", audio = AudioSettings(micDeviceName = "SECRETLEGACYHEADSET")),
        ),
    )

    @Test
    fun `ничто опознающее владельца не попадает в выгрузку`() {
        val json = Diagnostics.redactedJson(settings())
        assertFalse("ключ RTMP из пути baseUrl", json.contains("SECRETKEY"))
        assertFalse("srtauth из streamId", json.contains("SECRETAUTH"))
        assertFalse("адрес личного сервера", json.contains("example.com"))
        assertFalse("имя профиля", json.contains("twitch"))
        assertFalse("пароль Moblink", json.contains("SECRETRELAY"))
        assertFalse("секрет VK", json.contains("SECRETVK"))
        assertFalse("имя канала", json.contains("SECRETCHANNEL"))
        assertFalse("токен виджета доната", json.contains("SECRETTOKEN"))
        assertFalse("токен в старом поле overlayUrl", json.contains("SECRETWIDGET"))
        assertFalse("имя сцены", json.contains("SECRETSCENE"))
        assertFalse("путь к файлу заставки", json.contains("SECRETPATH"))
        assertFalse("токены Twitch", json.contains("SECRETACCESS") || json.contains("SECRETREFRESH"))
        assertFalse("ник Twitch", json.contains("SECRETLOGIN"))
        assertFalse("ключи Kick", json.contains("SECRETKICK"))
        assertFalse("имя гарнитуры", json.contains("SECRETHEADSET") || json.contains("SECRETLEGACYHEADSET"))
    }

    @Test
    fun `настройки, влияющие на поведение, остаются`() {
        val json = Diagnostics.redactedJson(settings())
        // Redaction must not become "remove everything just in case": a report
        // without settings is useless. The server type and its numeric values are
        // where investigation of any transport complaint begins.
        assertTrue("тип сервера", json.contains("SRTLA"))
        assertTrue("тип сервера", json.contains("RTMP"))
        // Non-default values are intentional: kotlinx omits fields equal to their
        // defaults, so checking a default value would be a no-op.
        assertTrue("задержка", json.contains("4321"))
        assertTrue("принудительный IPv4", json.contains("preferIpv4"))
    }
}
