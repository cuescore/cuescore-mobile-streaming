package app.brix.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * `SettingsStore` holds ALL of the user's configuration, so a mistake can lose
 * profiles, servers, overlays, and button layouts all at once. Yet it had no
 * tests at all: both the backup and recovery from corruption were supported only
 * by comments in the code.
 *
 * This tests not serialization (covered by [SettingsSerializationTest]), but
 * on-disk behavior — the reason this store is more complicated than `writeText`.
 */
class SettingsStoreTest {

    private lateinit var dir: File
    private lateinit var file: File

    @Before
    fun setUp() {
        dir = File.createTempFile("brix-settings", "").let { probe ->
            probe.delete()
            probe.mkdirs()
            probe
        }
        file = File(dir, "settings.json")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun store() = SettingsStore(file)

    private fun settingsNamed(name: String) = AppSettings(
        serverProfiles = listOf(
            ServerProfile(id = "s1", name = name, type = ServerType.SRTLA, baseUrl = "srtla://h:5000"),
        ),
    )

    @Test
    fun `отсутствующий файл — это не ошибка, а пустые настройки`() {
        val result = store().load()
        assertTrue(result.isSuccess)
        val loaded = result.getOrNull()!!
        // Everything is default EXCEPT the Moblink password: it is generated on
        // first install (14.09). This used to compare against `AppSettings()` as
        // a whole, and the test correctly failed when behavior changed. Also
        // except the onboarding flag: a fresh install has not completed it (16.09).
        assertEquals(AppSettings(moblink = loaded.moblink, onboardingDone = false), loaded)
        assertNotEquals("пароль не остался общеизвестным", "1234", loaded.moblink.password)
        assertEquals(8, loaded.moblink.password.length)
    }

    @Test
    fun `сохранённое читается обратно`() {
        val original = settingsNamed("мой сервер")
        store().save(original)

        val loaded = store().load()
        assertTrue(loaded.isSuccess)
        assertEquals(original.serverProfiles, loaded.getOrNull()?.serverProfiles)
    }

    @Test
    fun `запись не оставляет за собой временный файл`() {
        store().save(settingsNamed("a"))
        assertFalse(File(dir, "settings.json.tmp").exists())
    }

    @Test
    fun `резервная копия хранит ПРЕДЫДУЩЕЕ состояние, а не новое`() {
        val store = store()
        store.save(settingsNamed("первый"))
        store.save(settingsNamed("второй"))

        // The copy is made BEFORE replacing the file; otherwise it is useless:
        // both versions would be identical, leaving nothing to roll back to.
        val bak = File(dir, "settings.json.bak")
        assertTrue(bak.exists())
        assertTrue("в копии должно лежать предыдущее", bak.readText().contains("первый"))
        assertTrue(file.readText().contains("второй"))
    }

    @Test
    fun `испорченный файл восстанавливается из резервной копии`() {
        val store = store()
        store.save(settingsNamed("живой"))
        store.save(settingsNamed("новее"))
        // Interrupted write, dead flash storage, anything.
        file.writeText("{ это не json")

        val loaded = store.load()

        assertTrue("порченый основной файл не должен ронять загрузку", loaded.isSuccess)
        assertEquals("живой", loaded.getOrNull()?.serverProfiles?.first()?.name)
    }

    @Test
    fun `когда испорчены оба файла, загрузка честно возвращает ошибку`() {
        val store = store()
        store.save(settingsNamed("a"))
        store.save(settingsNamed("b"))
        file.writeText("{ мусор")
        File(dir, "settings.json.bak").writeText("тоже мусор")

        val loaded = store.load()

        // We must not silently substitute defaults here: the caller must learn
        // that the configuration was lost and tell the user.
        assertTrue(loaded.isFailure)
    }

    @Test
    fun `испорченный файл без резервной копии — тоже ошибка, а не тишина`() {
        file.writeText("{ мусор")
        assertTrue(store().load().isFailure)
    }

    @Test
    fun `при загрузке применяется миграция старого поля url`() {
        // The schema changed `url` to `baseUrl`; old files remain on devices.
        file.writeText(
            """
            {
              "serverProfiles": [
                { "id": "s1", "name": "старый", "type": "SRTLA", "url": "srtla://old:5000" }
              ]
            }
            """.trimIndent(),
        )

        val server = store().load().getOrNull()?.serverProfiles?.first()

        assertEquals("srtla://old:5000", server?.baseUrl)
        assertNull("после миграции старое поле должно опустеть", server?.url)
    }
}
