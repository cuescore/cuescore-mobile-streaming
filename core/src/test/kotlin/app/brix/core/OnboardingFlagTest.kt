package app.brix.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * First-run wizard completion flag (16.09).
 *
 * One error shows the wizard to a user with a configured stream; the opposite
 * hides it from newcomers. Test with real JSON: the scheme relies on kotlinx
 * omitting default values.
 */
class OnboardingFlagTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun roundTrip(s: AppSettings): AppSettings =
        json.decodeFromString<AppSettings>(json.encodeToString(s)).migrate()

    @Test
    fun `файл до появления мастера считается пройденным`() {
        val old = json.decodeFromString<AppSettings>("""{"serverProfiles":[]}""")
        assertEquals(true, old.migrate().onboardingDone)
    }

    @Test
    fun `свежая установка мастер ещё не прошла и это переживает запись`() {
        assertEquals(false, roundTrip(defaultAppSettings()).onboardingDone)
    }

    @Test
    fun `пройденный мастер остаётся пройденным`() {
        val done = defaultAppSettings().copy(onboardingDone = true)
        assertEquals(true, roundTrip(done).onboardingDone)
    }

    @Test
    fun `verified CueScore player survives saving and restarting the guide`() {
        val player = CueScorePlayer(
            id = "12345",
            name = "Test Player",
            imageUrl = "https://img.cuescore.com/player.png",
            countryImageUrl = "https://cuescore.com/img/flags/no.png",
            countryName = "Norway",
        )
        val completed = roundTrip(defaultAppSettings().copy(onboardingDone = true, cueScorePlayer = player))
        val restarted = roundTrip(completed.copy(onboardingDone = false))

        assertEquals(false, restarted.onboardingDone)
        assertEquals(player, restarted.cueScorePlayer)
    }

    @Test
    fun `verified CueScore player is saved before guide completion`() {
        val player = CueScorePlayer("12345", "Test Player")
        val saved = roundTrip(defaultAppSettings().copy(cueScorePlayer = player))

        assertEquals(false, saved.onboardingDone)
        assertEquals(player, saved.cueScorePlayer)
    }

    @Test
    fun `older settings have no saved CueScore player`() {
        val old = json.decodeFromString<AppSettings>("""{"onboardingDone":true}""").migrate()

        assertNull(old.cueScorePlayer)
    }
}
