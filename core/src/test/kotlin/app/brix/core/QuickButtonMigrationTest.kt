package app.brix.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.serialization.json.Json

class QuickButtonMigrationTest {
    @Test
    fun `default microphone replaces camera switch without corner duplicate`() {
        val config = QuickButtonConfig()
        assertFalse(config.slots.any { it.action == ButtonAction.FLIP })
        assertEquals(
            QuickButtonSlot(ButtonAction.MUTE, QuickButtonWidth.SMALL, 0, QuickButtonColumn.LEFT),
            config.slots.single { it.action == ButtonAction.MUTE },
        )
        assertNull(config.cornerSlotAction)
    }

    @Test
    fun `existing camera slot becomes microphone and preserves other controls`() {
        val camera = QuickButtonSlot(ButtonAction.FLIP, QuickButtonWidth.SMALL, 0, QuickButtonColumn.LEFT)
        val torch = QuickButtonSlot(ButtonAction.TORCH, QuickButtonWidth.SMALL, 0, QuickButtonColumn.RIGHT)
        val old = QuickButtonConfig(listOf(camera, torch), ButtonAction.MUTE)
        val migrated = defaultAppSettings().copy(quickButtons = old).migrate().quickButtons

        assertEquals(listOf(torch, camera.copy(action = ButtonAction.MUTE)), migrated.slots)
        assertNull(migrated.cornerSlotAction)
        assertEquals(migrated, migrated.withMicrophoneInCameraSlot())
    }

    @Test
    fun `custom layout without camera switch remains unchanged`() {
        val config = QuickButtonConfig(
            slots = listOf(QuickButtonSlot(ButtonAction.INFO, QuickButtonWidth.LARGE, 1, QuickButtonColumn.NULL)),
            cornerSlotAction = ButtonAction.MUTE,
        )
        assertEquals(config, config.withMicrophoneInCameraSlot())
    }

    @Test
    fun `legacy mascot slots and corner are removed without losing other controls`() {
        val settings = Json.decodeFromString<AppSettings>(
            """
            {
                "quickButtons": {
                    "slots": [
                        {"action":"MASCOT","width":"SMALL","rowIndex":0,"columnPosition":"LEFT"},
                        {"action":"TORCH","width":"SMALL","rowIndex":0,"columnPosition":"RIGHT"}
                    ],
                    "cornerSlotAction":"MASCOT"
                }
            }
            """.trimIndent(),
        ).migrate()

        assertEquals(listOf(ButtonAction.TORCH), settings.quickButtons.slots.map { it.action })
        assertNull(settings.quickButtons.cornerSlotAction)
        assertEquals(settings.quickButtons, settings.quickButtons.withoutMascot())
    }
}
